package dev.jvault.content;

import dev.jvault.crypto.envelope.ContentCipher;
import dev.jvault.crypto.envelope.KeyLocator;
import dev.jvault.crypto.envelope.ObjectIdentity;
import dev.jvault.storage.spi.ContentStore;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.StoredObjectRef;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores and retrieves externally placed content: encrypt, store, record — and the reverse.
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
    private final Path spoolDirectory;

    public ContentService(ContentCipher cipher,
                          ContentStore store,
                          ContentMetadataRepository metadata,
                          IdGenerator ids,
                          Clock clock,
                          String tenant) {
        this(cipher, store, metadata, ids, clock, tenant, null);
    }

    /**
     * @param spoolDirectory where ciphertext is spooled while streaming; {@code null} uses the
     *                       system temporary directory
     */
    public ContentService(ContentCipher cipher,
                          ContentStore store,
                          ContentMetadataRepository metadata,
                          IdGenerator ids,
                          Clock clock,
                          String tenant,
                          Path spoolDirectory) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.store = Objects.requireNonNull(store, "store");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.spoolDirectory = spoolDirectory;
    }

    /** Convenience for the small text parts — descriptions, comments, field values. */
    public ContentRecord storeText(PartDescriptor descriptor, String text) {
        return store(descriptor, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Encrypts and stores a new part from a stream, returning its permanent reference.
     *
     * <p>Memory stays bounded regardless of size: the ciphertext is spooled to a temporary file
     * and streamed into the backend from there. Spooling rather than piping is a deliberate
     * choice — a piped encrypt-to-store handoff needs a second thread and turns any backend
     * hiccup into a deadlock risk, whereas a spool file is boring, restartable, and holds
     * <em>ciphertext</em>, so a crash leaves nothing readable behind.
     */
    public ContentRecord store(PartDescriptor descriptor, InputStream content) {
        return writeVersion(ids.newContentRef(), 1, descriptor, content);
    }

    /**
     * Adds a version to an existing part.
     *
     * <p>The {@code contentRef} — and therefore every link already written into Jira, pasted into
     * a chat, or bookmarked — is unchanged. A new version is a new object at a new key, not an
     * overwrite, which is what makes version history and restore work identically on backends
     * that have no versioning of their own (docs/08-storage.md 8.2).
     */
    public ContentRecord addVersion(String contentRef, PartDescriptor descriptor, InputStream content) {
        int next = metadata.versionsOf(contentRef).size() + 1;
        return writeVersion(contentRef, next, descriptor, content);
    }

    private ContentRecord writeVersion(String contentRef,
                                       int versionNo,
                                       PartDescriptor descriptor,
                                       InputStream content) {
        String versionId = ids.newVersionId();
        var identity = new ObjectIdentity(contentRef, versionId);

        Path spool = null;
        try {
            spool = createSpoolFile(contentRef);

            ContentCipher.EncryptionResult result;
            try (OutputStream out = Files.newOutputStream(spool)) {
                result = cipher.encrypt(identity, descriptor.keyRing(), content, out);
            }

            long ciphertextSize = Files.size(spool);
            StoredObjectRef stored;
            try (InputStream ciphertext = Files.newInputStream(spool)) {
                stored = store.put(ContentStore.PutRequest.of(
                        new ObjectKey(tenant, contentRef, versionId), ciphertext, ciphertextSize));
            }

            var record = new ContentRecord(contentRef, versionId, versionNo,
                    descriptor.ticketRef(), descriptor.partType(), descriptor.fieldKey(),
                    descriptor.classification(), descriptor.keyRing(),
                    result.header().kekId(), result.header().wrappedDataKey(),
                    result.plaintextSha256(), result.ciphertextSha256(), result.plaintextSize(),
                    descriptor.mediaType(), descriptor.displayName(), descriptor.jiraSurrogate(),
                    stored, clock.instant());

            metadata.record(record);
            return record;

        } catch (IOException e) {
            throw new ContentException("could not store content for " + contentRef, e);
        } finally {
            deleteQuietly(spool);
        }
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
        return open(metadata.findCurrent(contentRef)
                .orElseThrow(() -> new ContentException("no content at " + contentRef, null)));
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

    private Path createSpoolFile(String contentRef) throws IOException {
        return spoolDirectory == null
                ? Files.createTempFile("jvault-", ".enc")
                : Files.createTempFile(Files.createDirectories(spoolDirectory), "jvault-", ".enc");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The spool holds ciphertext, so a leftover file is litter rather than exposure.
            // The orphan collector sweeps the spool directory.
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
}
