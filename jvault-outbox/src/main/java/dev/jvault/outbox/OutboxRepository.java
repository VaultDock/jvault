package dev.jvault.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the outbox.
 *
 * <p>The production adapter is PostgreSQL, and two of its properties are part of the contract
 * rather than implementation detail:
 *
 * <ul>
 *   <li>{@link #append} is idempotent on {@code (ticketRef, effectKey)} — backed by a unique
 *       constraint. This is what makes appending the same effect twice harmless, which in turn
 *       is what lets callers retry without reasoning about whether they already enqueued.</li>
 *   <li>{@link #claim} must not hand the same entry to two dispatchers. In PostgreSQL that is
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED}; any adapter must provide the equivalent, or
 *       two nodes will write the same effect to Jira concurrently.</li>
 * </ul>
 */
public interface OutboxRepository {

    /**
     * Enqueues an effect, or returns the existing entry if this {@code (ticketRef, effectKey)}
     * is already enqueued. Never creates a duplicate.
     */
    OutboxEntry append(OutboxEntry entry);

    /**
     * Claims up to {@code limit} entries that are due, marking them {@link OutboxState#CLAIMED}
     * so no other dispatcher can take them.
     *
     * <p>Returned in {@code createdAt} order, so effects against one ticket keep the order they
     * were enqueued in — a remote link must not be attached before the issue it points at exists.
     */
    List<OutboxEntry> claim(int limit, Instant now);

    void save(OutboxEntry entry);

    Optional<OutboxEntry> find(UUID id);

    Optional<OutboxEntry> findByEffect(String ticketRef, String effectKey);

    /** Entries stuck {@link OutboxState#IN_FLIGHT}, which the ambiguity resolver owns. */
    /**
     * Every effect queued for one ticket, whatever its state.
     *
     * <p>For answering "why is this ticket stuck", which a ticket record cannot answer on its
     * own: it knows it failed, and the entry that failed knows what Jira said.
     */
    List<OutboxEntry> findForTicket(String ticketRef);

    List<OutboxEntry> findInFlightOlderThan(Instant threshold);

    /** Releases entries claimed by a dispatcher that died before sending them. */
    int releaseStaleClaims(Instant claimedBefore);

    /**
     * Moves every not-yet-terminal effect for a ticket onto a new lane.
     *
     * <p>Called once the ticket's Jira issue exists. Until then effects sit on the pending lane
     * {@code ticket:<ref>}; afterwards they must serialise on the issue, which is both the correct
     * per-issue rate-limit bucket and how the gateway knows what to target.
     *
     * @return how many entries were moved
     */
    int moveToLane(String ticketRef, String newLane);
}
