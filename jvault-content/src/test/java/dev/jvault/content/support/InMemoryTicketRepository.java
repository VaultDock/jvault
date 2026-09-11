package dev.jvault.content.support;

import dev.jvault.content.TicketRecord;
import dev.jvault.content.TicketRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reproduces the property the real adapter gets from a unique constraint on
 * {@code (space_id, dedupe_key)}: {@link #reserve} is atomic, so two callers racing the same
 * dedupe key cannot both be told they created it.
 */
public final class InMemoryTicketRepository implements TicketRepository {

    private final Map<String, TicketRecord> byRef = new LinkedHashMap<>();
    private final Map<String, String> byDedupeKey = new LinkedHashMap<>();

    @Override
    public synchronized Reservation reserve(TicketRecord candidate) {
        if (candidate.dedupeKey() == null) {
            byRef.put(candidate.ticketRef(), candidate);
            return new Reservation(candidate, true);
        }
        String key = dedupeKeyOf(candidate.deploymentId(), candidate.projectKey(),
                candidate.dedupeKey());
        String existing = byDedupeKey.putIfAbsent(key, candidate.ticketRef());
        if (existing != null) {
            return new Reservation(byRef.get(existing), false);
        }
        byRef.put(candidate.ticketRef(), candidate);
        return new Reservation(candidate, true);
    }

    @Override
    public synchronized void save(TicketRecord record) {
        byRef.put(record.ticketRef(), record);
    }

    @Override
    public synchronized Optional<TicketRecord> find(String ticketRef) {
        return Optional.ofNullable(byRef.get(ticketRef));
    }

    @Override
    public synchronized Optional<TicketRecord> findByDedupeKey(String deploymentId,
                                                               String projectKey,
                                                               String dedupeKey) {
        return Optional.ofNullable(byDedupeKey.get(dedupeKeyOf(deploymentId, projectKey, dedupeKey)))
                .map(byRef::get);
    }

    private static String dedupeKeyOf(String deploymentId, String projectKey, String dedupeKey) {
        return deploymentId + "|" + projectKey + "|" + dedupeKey;
    }
}
