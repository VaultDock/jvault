package dev.jvault.outbox.backoff;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter, following Atlassian's own published guidance for their rate
 * limits: 2 s base, doubling to a ~30 s cap, a 0.7–1.3 jitter multiplier, around four attempts,
 * and {@code Retry-After} honoured as a floor (docs/00-verified-capabilities.md 0.3).
 *
 * <p>Jitter is not decoration. Without it, every jvault node that got throttled in the same
 * second retries in the same second, and the herd re-forms on every cycle.
 *
 * <p>Pure: the clock and the randomness are both parameters, so the schedule is exactly testable.
 *
 * @param base        delay before the first retry
 * @param cap         ceiling on the exponential term, before jitter
 * @param jitterMin   lower bound of the multiplier
 * @param jitterMax   upper bound of the multiplier
 * @param maxAttempts attempts after which an entry is abandoned
 */
public record BackoffPolicy(Duration base,
                            Duration cap,
                            double jitterMin,
                            double jitterMax,
                            int maxAttempts) {

    public BackoffPolicy {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(cap, "cap");
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must be at least base");
        }
        if (jitterMin <= 0 || jitterMax < jitterMin) {
            throw new IllegalArgumentException("require 0 < jitterMin <= jitterMax");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    /** Atlassian's documented recommendation. */
    public static BackoffPolicy atlassianDefault() {
        return new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30), 0.7, 1.3, 4);
    }

    /**
     * @param attempt 1-based: {@code 1} is the delay before the second try
     */
    public Duration delayFor(int attempt, RandomGenerator rng) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt is 1-based");
        }
        long baseMillis = base.toMillis();
        long capMillis = cap.toMillis();

        // Shift rather than Math.pow, and clamp before multiplying, so a large attempt count
        // cannot overflow into a negative delay.
        int shift = Math.min(attempt - 1, 32);
        long exponential = (shift >= 32 || baseMillis > capMillis >> shift)
                ? capMillis
                : baseMillis << shift;
        long clamped = Math.min(exponential, capMillis);

        double multiplier = jitterMin + rng.nextDouble() * (jitterMax - jitterMin);
        return Duration.ofMillis(Math.max(1, Math.round(clamped * multiplier)));
    }

    /**
     * When to try again.
     *
     * @param retryAfter Jira's {@code Retry-After}, or {@code null}. Treated as a <em>floor</em>,
     *                   never a replacement: Jira telling us to wait 1 s does not mean we should
     *                   ignore our own backoff after four failures.
     */
    public Instant nextAttemptAt(Instant now, int attempt, Duration retryAfter, RandomGenerator rng) {
        Duration delay = delayFor(attempt, rng);
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        return now.plus(delay);
    }

    /**
     * Whether an entry that has consumed {@code attempts} error-attempts has any left.
     *
     * <p>Throttles do not consume attempts, so a heavily rate-limited system retries as long as
     * Jira keeps saying "later" without ever exhausting the budget that exists to stop a
     * genuinely broken operation.
     */
    public boolean hasAttemptsLeft(int attempts) {
        return attempts < maxAttempts;
    }
}
