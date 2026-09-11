package dev.jvault.content;

import dev.jvault.crypto.envelope.ContentCipher;
import dev.jvault.crypto.envelope.KeyLocator;
import dev.jvault.crypto.envelope.ObjectIdentity;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.placement.PartType;
import dev.jvault.storage.spi.ContentStore;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.StoredObjectRef;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores and retrieves externally placed content: encrypt, put, record — and the reverse.
 *
 * <p>The ordering matters and is not arbitrary. Content is written <em>before</em> the Jira issue
 * exists (docs/03-architecture.md 3.5), because content without an issue is an orphan a reconciler
 * can quietly clean up, whereas an issue without its content is a visible broken placeholder in
 * Jira that a user sees and cannot fix.
 *
 * <p>Within this class the order is encrypt → store → record. A crash between store and record
 * leaves an object with no metadata row, which the orphan collector removes after a grace period.
 * The reverse order would leave a metadata row promising bytes that were never written — a
 * dangling reference that looks like data loss rather than like litter.
 */
public final class ContentService {

    private final ContentCipher cipher;
    private final ContentStore store;
    private final ContentMetadataRepository metadata;
    private final IdGenerator ids;
    private final Clock clock;
    private final String tenant;

    public ContentService(ContentCipher cipher,
                          ContentStore store,
                          ContentMetadataRepository metadata,
                          IdGenerator ids,
                          Clock clock,
                          String tenant) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.store = Objects.requireNonNull(store, "store");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
    }

    /**
     * Encrypts and stores a new part, returning its permanent reference.
     *
     * @param request what to store and under which policy
     */
    public ContentRecord store(StoreRequest request) {
        String contentRef = ids.newContentRef();
        return writeVersion(contentRef, 1, request);
    }

    /**
     * Adds a version to an existing part.
     *
     * <p>The {@code contentRef} — and therefore every link already written into Jira, pasted into
     * a chat, or bookmarked — is unchanged. A new version is a new object at a new key, not an
     * overwrite, which is what makes version history and restore possible on backends that have
     * no versioning of their own (docs/08-storage.md 8.2).
     */
    public ContentRecord addVersion(String contentRef, StoreRequest request) {
        int next = metadata.versionsOf(contentRef).size() + 1;
        return writeVersion(contentRef, next, request);
    }

    private ContentRecord writeVersion(String contentRef, int versionNo, StoreRequest request) {
        String versionId = ids.newVersionId();
        var identity = new ObjectIdentity(contentRef, versionId);

        // Encrypted first, so the store never sees plaintext even transiently.
        var encrypted = new ByteArrayOutputStream();
        ContentCipher.EncryptionResult result;
        try {
            result = cipher.encrypt(identity, request.keyRing(),
                    new ByteArrayInputStream(request.content()), encrypted);
        } catch (IOException e) {
            throw new ContentException("could not encrypt content for " + contentRef, e);
        }

        byte[] ciphertext = encrypted.toByteArray();
        StoredObjectRef stored = store.put(ContentStore.PutRequest.of(
                new ObjectKey(tenant, contentRef, versionId),
                new ByteArrayInputStream(ciphertext), ciphertext.length));

        var record = new ContentRecord(contentRef, versionId, versionNo, request.ticketRef(),
                request.partType(), request.fieldKey(), request.classification(),
                request.keyRing(), result.header().kekId(), result.header().wrappedDataKey(),
                result.plaintextSha256(), result.ciphertextSha256(), result.plaintextSize(),
                request.mediaType(), stored, clock.instant());

        metadata.record(record);
        return record;
    }

    /**
     * Opens a decrypting stream over the current version.
     *
     * <p>The wrapped key comes from the metadata record, not from the object header. That is the
     * difference between rotation being a metadata operation and being a ten-terabyte rewrite.
     *
     * <p><strong>This method does not authorize.</strong> Authorization belongs to the content
     * authorization service and must happen on every request, including previews, ranges and
     * historical versions (docs/11-authorization.md 11.5). A caller reaching this method has
     * already made that decision, and the API layer is where it is made.
     */
    public InputStream open(String contentRef) {
        ContentRecord record = metadata.findCurrent(contentRef)
                .orElseThrow(() -> new ContentException("no content at " + contentRef, null));
        return open(record);
    }

    public InputStream open(ContentRecord record) {
        try {
            return cipher.decrypt(
                    new ObjectIdentity(record.contentRef(), record.versionId()),
                    record.keyRing(),
                    store.open(record.storedObject()),
                    KeyLocator.of(record.kekId(), record.wrappedDataKey()));
        } catch (IOException e) {
            throw new ContentException("could not open " + record.contentRef(), e);
        }
    }

    public Optional<ContentRecord> find(String contentRef) {
        return metadata.findCurrent(contentRef);
    }

    /**
     * @param content the plaintext. Held in memory here because the parts jvault externalises in
     *                the MVP — descriptions, comments, field values — are small. Attachments go
     *                through a streaming path instead; see the upload flow in
     *                docs/06-rest-api.md, which is why this takes bytes rather than a stream.
     */
    public record StoreRequest(String ticketRef,
                               PartType partType,
                               String fieldKey,
                               byte[] content,
                               String mediaType,
                               Classification classification,
                               String keyRing) {

        public StoreRequest {
            Objects.requireNonNull(ticketRef, "ticketRef");
            Objects.requireNonNull(partType, "partType");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(keyRing, "keyRing");
            mediaType = mediaType == null ? "text/plain" : mediaType;
        }
    }

    /** Injected so tests get deterministic identifiers; production uses time-ordered UUIDs. */
    public interface IdGenerator {
        String newContentRef();

        String newVersionId();

        static IdGenerator random() {
            return new IdGenerator() {
                @Override
                public String newContentRef() {
                    return compact();
                }

                @Override
                public String newVersionId() {
                    return compact();
                }

                private String compact() {
                    // Opaque, and free of the hyphens ObjectKey rejects.
                    return java.util.UUID.randomUUID().toString().replace("-", "");
                }
            };
        }
    }

    public static class ContentException extends RuntimeException {
        public ContentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Identifiers only. */
    static Map<String, String> refOf(ContentRecord record) {
        return Map.of("contentRef", record.contentRef(), "versionId", record.versionId());
    }
}
