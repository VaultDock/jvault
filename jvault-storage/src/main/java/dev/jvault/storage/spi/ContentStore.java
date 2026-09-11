package dev.jvault.storage.spi;

import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.Optional;

/**
 * A pluggable backend for content bytes (docs/08-storage.md 8.1).
 *
 * <p>Everything reaching a {@code ContentStore} is already encrypted: the crypto layer sits
 * between the application and the store, so every backend gets identical confidentiality
 * regardless of whether it offers server-side encryption of its own. A store therefore never
 * sees plaintext and never needs a key.
 *
 * <p>Implementations must make {@link #put} atomic: a reader must see either the whole object or
 * no object. A half-written object that a later read accepts is worse than a failed write,
 * because the failure is silent and arrives much later.
 */
public interface ContentStore {

    /** Route configuration refers to backends by this name; it appears in {@link StoredObjectRef}. */
    String name();

    Capabilities capabilities();

    /**
     * Writes bytes at {@code key}.
     *
     * <p>Safe to repeat with identical input: a retry after an ambiguous failure overwrites the
     * same key with the same bytes rather than creating a second object. This is what lets the
     * content-first ordering in the creation path be retried without leaving orphans
     * (docs/03-architecture.md 3.5).
     */
    StoredObjectRef put(PutRequest request);

    /** @throws StorageException with code {@code OBJECT_NOT_FOUND} if it is not there */
    InputStream open(StoredObjectRef ref);

    /**
     * A seekable view, for ranged reads.
     *
     * @throws UnsupportedOperationException when {@link Capabilities#rangeReads()} is false;
     *                                       callers should fall back to {@link #open} and discard
     */
    SeekableByteChannel openChannel(StoredObjectRef ref);

    void delete(StoredObjectRef ref, DeleteMode mode);

    /** Restores a soft-deleted object. */
    void restore(StoredObjectRef ref);

    Optional<ObjectStat> stat(StoredObjectRef ref);

    /** Checked at startup and on a schedule, so a misconfigured route fails visibly. */
    Health health();

    /**
     * @param contentLength expected length, or {@code -1} when unknown. Checked against
     *                      {@link Capabilities#maxObjectBytes()} before any bytes are written.
     * @param mediaType     always {@code application/octet-stream} for encrypted content: the
     *                      real media type is metadata in the database, because a backend that
     *                      knows it holds a PDF has learned something about the content
     */
    record PutRequest(ObjectKey key, InputStream content, long contentLength, String mediaType) {

        public PutRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(content, "content");
            mediaType = mediaType == null ? "application/octet-stream" : mediaType;
        }

        public static PutRequest of(ObjectKey key, InputStream content, long contentLength) {
            return new PutRequest(key, content, contentLength, "application/octet-stream");
        }
    }

    record Health(boolean healthy, String detail) {
        public static Health up() {
            return new Health(true, "ok");
        }

        public static Health down(String detail) {
            return new Health(false, detail);
        }
    }
}
