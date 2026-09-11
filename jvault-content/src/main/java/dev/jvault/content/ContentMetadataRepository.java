package dev.jvault.content;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for content metadata.
 *
 * <p>Kept separate from the bytes on purpose. The metadata store is the authoritative record —
 * it holds the wrapped data keys, the digests, and the mapping from a permanent {@code contentRef}
 * to whichever object currently holds the bytes. Losing it turns every stored object into
 * anonymous ciphertext, which is why {@link ContentRecord} is also written into the object header
 * as a disaster-recovery fallback (docs/08-storage.md 8.7).
 */
public interface ContentMetadataRepository {

    void record(ContentRecord record);

    /** The current version of a part. */
    Optional<ContentRecord> findCurrent(String contentRef);

    Optional<ContentRecord> findVersion(String contentRef, int versionNo);

    List<ContentRecord> versionsOf(String contentRef);

    List<ContentRecord> partsOf(String ticketRef);
}
