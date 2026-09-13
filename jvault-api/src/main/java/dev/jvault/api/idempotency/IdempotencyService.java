package dev.jvault.api.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Makes a creating POST safe to repeat.
 *
 * <p>A client that times out waiting for a ticket to be created has no way of knowing whether it
 * was — the same problem the ambiguity protocol solves on the Jira side, one layer up. Without a
 * key, their only options are to retry and risk a duplicate, or not to retry and risk losing the
 * work. The key gives them a third.
 *
 * <p>Three outcomes, and the distinction between them is the whole design
 * (docs/06-rest-api.md 6.1):
 *
 * <ul>
 *   <li><strong>Same key, same request, finished</strong> — replay the original response. The
 *       client cannot tell whether their first attempt or this one produced it, which is the
 *       point.</li>
 *   <li><strong>Same key, same request, still running</strong> — 409 and a {@code Retry-After}.
 *       Not an error so much as "ask again shortly"; returning 202 here would let a client
 *       believe a second ticket was queued.</li>
 *   <li><strong>Same key, different request</strong> — 409, refused. Almost always a client bug
 *       (a key reused across a loop), and silently serving the first response for a different
 *       request would be far worse than saying so.</li>
 * </ul>
 *
 * <p>The fingerprint is a hash of the request body, so a replay is recognised without keeping the
 * body itself — which matters here, because that body may carry content destined for the vault.
 */
public final class IdempotencyService {

    /** Long enough to cover a client's retry budget, short enough to bound the store. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final IdempotencyStore store;
    private final Clock clock;
    private final Duration retention;

    public IdempotencyService(IdempotencyStore store, Clock clock) {
        this(store, clock, DEFAULT_RETENTION);
    }

    public IdempotencyService(IdempotencyStore store, Clock clock, Duration retention) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    /**
     * Claims a key for this request, or reports what already happened under it.
     *
     * <p>The claim is atomic in the store, not a check followed by a write: two identical requests
     * arriving together is exactly the case a key exists to handle, and a read-then-write here
     * would let both through.
     */
    public Decision begin(String key, String principal, String requestBody) {
        if (key == null || key.isBlank()) {
            return Decision.proceed(null);
        }
        String scoped = scope(key, principal);
        String fingerprint = fingerprint(requestBody);
        Instant now = clock.instant();

        Optional<IdempotencyRecord> existing = store.claim(
                new IdempotencyRecord(scoped, fingerprint, State.IN_PROGRESS, 0, null, now));

        if (existing.isEmpty()) {
            return Decision.proceed(scoped);
        }

        IdempotencyRecord record = existing.get();
        if (record.createdAt().plus(retention).isBefore(now)) {
            // Expired: the key is available again. A client retrying a day later is starting a
            // new request as far as anyone can tell.
            store.remove(scoped);
            return begin(key, principal, requestBody);
        }
        if (!record.fingerprint().equals(fingerprint)) {
            return Decision.conflictingFingerprint();
        }
        return record.state() == State.COMPLETED
                ? Decision.replay(record)
                : Decision.inProgress();
    }

    /** Records the response so a later replay returns exactly what the first caller saw. */
    public void complete(String scopedKey, int status, String responseBody) {
        if (scopedKey == null) {
            return;
        }
        store.complete(scopedKey, status, responseBody, clock.instant());
    }

    /**
     * Releases a key whose request failed.
     *
     * <p>Deliberate: a failed attempt should not lock the key for a day. The client is expected to
     * retry with the same key, and that retry has to be allowed to run.
     */
    public void abandon(String scopedKey) {
        if (scopedKey != null) {
            store.remove(scopedKey);
        }
    }

    /**
     * Keys are scoped to the caller.
     *
     * <p>Two clients picking the same key — a UUID collision is unlikely, "retry-1" is not —
     * must not be able to read each other's responses.
     */
    private static String scope(String key, String principal) {
        return (principal == null ? "anonymous" : principal) + "|" + key;
    }

    static String fingerprint(String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = (requestBody == null ? "" : requestBody).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    public enum State {IN_PROGRESS, COMPLETED}

    /**
     * @param scopedKey the key to pass back to {@link #complete}, or {@code null} when the caller
     *                  supplied no key and nothing is being tracked
     */
    public record Decision(Kind kind, String scopedKey, IdempotencyRecord replay) {

        public enum Kind {PROCEED, REPLAY, IN_PROGRESS, CONFLICTING_FINGERPRINT}

        static Decision proceed(String scopedKey) {
            return new Decision(Kind.PROCEED, scopedKey, null);
        }

        static Decision replay(IdempotencyRecord record) {
            return new Decision(Kind.REPLAY, record.key(), record);
        }

        static Decision inProgress() {
            return new Decision(Kind.IN_PROGRESS, null, null);
        }

        static Decision conflictingFingerprint() {
            return new Decision(Kind.CONFLICTING_FINGERPRINT, null, null);
        }

        public boolean shouldProceed() {
            return kind == Kind.PROCEED;
        }
    }

    /**
     * @param fingerprint a hash of the request body, never the body — which may carry content
     *                    destined for the vault
     */
    public record IdempotencyRecord(String key,
                                    String fingerprint,
                                    State state,
                                    int status,
                                    String responseBody,
                                    Instant createdAt) {
    }

    /**
     * Persistence port.
     *
     * <p>{@link #claim} must be atomic — backed by an insert that fails on a unique key, not by a
     * read followed by a write. Two identical requests arriving at once is precisely the case an
     * idempotency key exists to handle.
     */
    public interface IdempotencyStore {

        /**
         * @return empty when the key was claimed by this call; the existing record otherwise
         */
        Optional<IdempotencyRecord> claim(IdempotencyRecord record);

        void complete(String key, int status, String responseBody, Instant when);

        void remove(String key);
    }
}
