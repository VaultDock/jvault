package dev.jvault.content;

import java.util.Optional;

/**
 * Persistence port for ticket records.
 *
 * <p>{@link #reserve} is the concurrency control for duplicate prevention. It must be atomic:
 * backed by a unique constraint on {@code (space_id, dedupe_key)}, not by a read-then-write check,
 * which would race between two consumers processing a replayed message at the same moment
 * (docs/04-data-model.md 4.2).
 */
public interface TicketRepository {

    /**
     * Claims a ticket reference for a dedupe key, or returns the existing one.
     *
     * @return whether this call created the reservation. {@code false} means another caller — or
     *         an earlier delivery of the same message — already has it, and the caller must not
     *         proceed to create a second Jira issue
     */
    Reservation reserve(TicketRecord candidate);

    void save(TicketRecord record);

    Optional<TicketRecord> find(String ticketRef);

    Optional<TicketRecord> findByDedupeKey(String deploymentId, String projectKey, String dedupeKey);

    record Reservation(TicketRecord record, boolean created) {
    }
}
