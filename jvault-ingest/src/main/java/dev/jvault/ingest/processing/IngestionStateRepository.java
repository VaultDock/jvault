package dev.jvault.ingest.processing;

import java.util.Optional;

/**
 * Persistence port for message processing state.
 *
 * <p>Two uniqueness guarantees, doing different jobs, both backed by database constraints rather
 * than by read-then-write checks (docs/04-data-model.md 4.8):
 *
 * <ul>
 *   <li>{@code (topic, partition, offset)} — catches <em>redelivery</em> of the same message.</li>
 *   <li>{@code (mappingId, dedupeKey)} — catches <em>republication</em> of the same business
 *       event on a different offset, which is what a topic replay or a producer retry looks
 *       like.</li>
 * </ul>
 */
public interface IngestionStateRepository {

    Optional<IngestedMessage> findByOffset(String topic, int partition, long offset);

    Optional<IngestedMessage> findByDedupeKey(String mappingId, String dedupeKey);

    /**
     * Claims a business key, atomically.
     *
     * @return {@code false} when the key is already claimed, meaning this event has already been
     *         turned into a ticket and must not produce another
     */
    boolean reserveDedupeKey(String mappingId, String dedupeKey, IngestedMessage message);

    void save(IngestedMessage message);
}
