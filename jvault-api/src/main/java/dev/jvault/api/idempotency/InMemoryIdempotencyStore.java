package dev.jvault.api.idempotency;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency store.
 *
 * <p>Correct for a single node and wrong the moment there are two, because a key claimed on one
 * node is invisible to the other — which is exactly the case an idempotency key exists to handle.
 * The production implementation is a table with a unique key on the scoped id; this exists so the
 * API is runnable and testable before that lands, and it says so rather than pretending.
 *
 * <p>{@code claim} uses {@code putIfAbsent} rather than a containsKey-then-put, so that two
 * identical requests arriving together behave the same way here as they will against a database.
 */
public final class InMemoryIdempotencyStore implements IdempotencyService.IdempotencyStore {

    private final Map<String, IdempotencyService.IdempotencyRecord> records =
            new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyService.IdempotencyRecord> claim(
            IdempotencyService.IdempotencyRecord record) {
        return Optional.ofNullable(records.putIfAbsent(record.key(), record));
    }

    @Override
    public void complete(String key, int status, String responseBody, Instant when) {
        records.computeIfPresent(key, (k, existing) -> new IdempotencyService.IdempotencyRecord(
                k, existing.fingerprint(), IdempotencyService.State.COMPLETED, status,
                responseBody, existing.createdAt()));
    }

    @Override
    public void remove(String key) {
        records.remove(key);
    }

    public int size() {
        return records.size();
    }
}
