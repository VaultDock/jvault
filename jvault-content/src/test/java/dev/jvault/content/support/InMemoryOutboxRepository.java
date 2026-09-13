package dev.jvault.content.support;

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

/** As in jvault-outbox: append idempotent on (ticketRef, effectKey), claim never double-hands. */
public final class InMemoryOutboxRepository implements OutboxRepository {

    private final Map<UUID, OutboxEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized OutboxEntry append(OutboxEntry entry) {
        return findByEffect(entry.ticketRef(), entry.effectKey()).orElseGet(() -> {
            entries.put(entry.id(), entry);
            return entry;
        });
    }

    @Override
    public synchronized List<OutboxEntry> claim(int limit, Instant now) {
        var claimed = new ArrayList<OutboxEntry>();
        entries.values().stream()
                .filter(e -> e.state().isClaimable())
                .filter(e -> !e.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(OutboxEntry::createdAt))
                .limit(limit)
                .forEach(e -> {
                    OutboxEntry marked = e.withState(OutboxState.CLAIMED);
                    entries.put(marked.id(), marked);
                    claimed.add(marked);
                });
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
}
