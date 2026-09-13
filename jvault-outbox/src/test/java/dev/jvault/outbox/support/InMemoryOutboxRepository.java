package dev.jvault.outbox.support;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxRepository;
import dev.jvault.outbox.OutboxState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link OutboxRepository} for tests.
 *
 * <p>Reproduces the two contract properties the production PostgreSQL adapter provides:
 * {@code append} is idempotent on {@code (ticketRef, effectKey)}, and {@code claim} never hands
 * the same entry to two callers. Without the second, a test would pass against this fake and the
 * real system would double-write to Jira.
 */
public final class InMemoryOutboxRepository implements OutboxRepository {

    private final Map<UUID, OutboxEntry> entries = new LinkedHashMap<>();
    private int claimCalls;

    @Override
    public synchronized OutboxEntry append(OutboxEntry entry) {
        return findByEffect(entry.ticketRef(), entry.effectKey())
                .orElseGet(() -> {
                    entries.put(entry.id(), entry);
                    return entry;
                });
    }

    @Override
    public synchronized List<OutboxEntry> claim(int limit, Instant now) {
        claimCalls++;
        List<OutboxEntry> due = entries.values().stream()
                .filter(e -> e.state().isClaimable())
                .filter(e -> !e.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(OutboxEntry::createdAt))
                .limit(limit)
                .toList();

        var claimed = new ArrayList<OutboxEntry>();
        for (OutboxEntry entry : due) {
            OutboxEntry marked = entry.withState(OutboxState.CLAIMED);
            entries.put(marked.id(), marked);
            claimed.add(marked);
        }
        return claimed;
    }

    @Override
    public synchronized void save(OutboxEntry entry) {
        entries.put(entry.id(), entry);
    }

    @Override
    public synchronized Optional<OutboxEntry> find(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public synchronized Optional<OutboxEntry> findByEffect(String ticketRef, String effectKey) {
        return entries.values().stream()
                .filter(e -> e.ticketRef().equals(ticketRef) && e.effectKey().equals(effectKey))
                .findFirst();
    }

    @Override
    public synchronized List<OutboxEntry> findForTicket(String ticketRef) {
        return entries.values().stream()
                .filter(e -> e.ticketRef().equals(ticketRef))
                .sorted(java.util.Comparator.comparing(OutboxEntry::createdAt)
                        .thenComparing(OutboxEntry::effectKey))
                .toList();
    }

    @Override
    public synchronized List<OutboxEntry> findInFlightOlderThan(Instant threshold) {
        return entries.values().stream()
                .filter(e -> e.state() == OutboxState.IN_FLIGHT)
                .filter(e -> e.attemptStartedAt() != null && e.attemptStartedAt().isBefore(threshold))
                .toList();
    }

    @Override
    public synchronized int releaseStaleClaims(Instant claimedBefore) {
        var stale = entries.values().stream()
                .filter(e -> e.state() == OutboxState.CLAIMED)
                .filter(e -> e.createdAt().isBefore(claimedBefore))
                .toList();
        stale.forEach(e -> entries.put(e.id(), e.withState(OutboxState.PENDING)));
        return stale.size();
    }

    @Override
    public synchronized int moveToLane(String ticketRef, String newLane) {
        var moved = entries.values().stream()
                .filter(e -> e.ticketRef().equals(ticketRef))
                .filter(e -> !e.state().isTerminal())
                .filter(e -> !newLane.equals(e.issueLane()))
                .toList();
        moved.forEach(e -> entries.put(e.id(), e.withIssueLane(newLane)));
        return moved.size();
    }

    public synchronized List<OutboxEntry> all() {
        return List.copyOf(entries.values());
    }

    public synchronized int claimCalls() {
        return claimCalls;
    }
}
