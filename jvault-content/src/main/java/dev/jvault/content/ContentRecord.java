package dev.jvault.content;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PartType;
import dev.jvault.storage.spi.StoredObjectRef;

import java.time.Instant;
import java.util.Objects;

/**
 * What jvault knows about one externally stored part, once it has been written.
 *
 * <p>{@code jiraSurrogate} is the text Jira holds in place of the content. It lives beside the
 * content rather than only inside the Jira issue so that the payload assembler can rebuild the
 * Jira write from jvault's own record — and so that a surrogate a user accidentally edits in Jira
 * can be restored (docs/12-reliability.md 12.1).
 *
 * <p>The wrapped data key lives here rather than only in the object header, and the database is
 * authoritative: a KEK rotation rewraps this and deliberately does not rewrite the object, so the
 * header's copy goes stale the moment a ring rotates (docs/09-encryption.md 9.5).
 *
 * @param contentRef the permanent public identifier — the basis of the Jira link, unchanged by
 *                   new versions, storage migrations or archival
 * @param displayName the original filename, which is frequently the most sensitive thing about a
 *                   file and is therefore carried as a {@link dev.jvault.domain.common.SensitiveValue}
 */
public record ContentRecord(String contentRef,
                            String versionId,
                            int versionNo,
                            String ticketRef,
                            PartType partType,
                            String fieldKey,
                            Classification classification,
                            String keyRing,
                            String kekId,
                            byte[] wrappedDataKey,
                            byte[] plaintextSha256,
                            byte[] ciphertextSha256,
                            long sizeBytes,
                            String mediaType,
                            SensitiveValue displayName,
                            String jiraSurrogate,
                            StoredObjectRef storedObject,
                            Instant createdAt) {

    public ContentRecord {
        Objects.requireNonNull(contentRef, "contentRef");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(partType, "partType");
        wrappedDataKey = wrappedDataKey.clone();
        plaintextSha256 = plaintextSha256.clone();
        ciphertextSha256 = ciphertextSha256.clone();
    }

    @Override
    public byte[] wrappedDataKey() {
        return wrappedDataKey.clone();
    }

    @Override
    public byte[] plaintextSha256() {
        return plaintextSha256.clone();
    }

    @Override
    public byte[] ciphertextSha256() {
        return ciphertextSha256.clone();
    }

    /** Identifiers only — safe to log, and safe to put in an outbox row. */
    @Override
    public String toString() {
        // Note what is absent: displayName. It is a SensitiveValue, so including it would print
        // the redaction marker rather than the name — but leaving it out entirely is clearer.
        return "ContentRecord[" + contentRef + " v" + versionNo + ", " + partType
                + (fieldKey == null ? "" : ":" + fieldKey) + ", " + sizeBytes + " bytes]";
    }
}
