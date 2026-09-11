package dev.jvault.outbox.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps jvault inside Jira's per-issue write limits: 20 operations per 2 seconds and 100 per
 * 30 seconds against a single issue (docs/00-verified-capabilities.md 0.3).
 *
 * <p>This is the binding constraint for ticket creation, not the global burst limit: creating one
 * ticket performs a create, a property write, a remote link per external part and possibly a
 * comment — several writes against <em>one</em> issue in quick succession. A global limiter would
 * happily let all of them through and Jira would still return 429.
 *
 * <p>Sliding windows rather than fixed ones, because a fixed window lets 20 writes land at the end
 * of one window and 20 more at the start of the next — 40 in a moment, which is exactly what the
 * limit forbids.
 *
 * <p>Not thread-safe; one instance belongs to one dispatcher, and lanes are the unit of
 * serialisation within it.
 */
public final class PerIssueRateLimiter {

    private final Map<String, Deque<Instant>> byLane = new HashMap<>();
    private final Window shortWindow;
    private final Window longWindow;

    /**
     * @param shortWindow e.g. 20 permits per 2 s
     * @param longWindow  e.g. 100 permits per 30 s
     */
    public PerIssueRateLimiter(Window shortWindow, Window longWindow) {
        this.shortWindow = Objects.requireNonNull(shortWindow, "shortWindow");
        this.longWindow = Objects.requireNonNull(longWindow, "longWindow");
    }

    /** Jira Cloud's documented per-issue limits. */
    public static PerIssueRateLimiter jiraCloudDefaults() {
        return new PerIssueRateLimiter(
                new Window(20, Duration.ofSeconds(2)),
                new Window(100, Duration.ofSeconds(30)));
    }

    /**
     * @return empty when a write may proceed now, otherwise how long to wait
     */
    public Optional<Duration> timeUntilPermitted(String lane, Instant now) {
        Deque<Instant> history = byLane.get(lane);
        if (history == null) {
            return Optional.empty();
        }
        prune(history, now);

        Duration shortWait = waitFor(history, shortWindow, now);
        Duration longWait = waitFor(history, longWindow, now);
        Duration wait = shortWait.compareTo(longWait) >= 0 ? shortWait : longWait;

        return wait.isZero() || wait.isNegative() ? Optional.empty() : Optional.of(wait);
    }

    /** Records that a write happened. Call only after the write is actually attempted. */
    public void record(String lane, Instant now) {
        byLane.computeIfAbsent(lane, k -> new ArrayDeque<>()).addLast(now);
    }

    /** Drops lanes with no recent activity, so the map does not grow without bound. */
    public void evictIdleLanes(Instant now) {
        byLane.entrySet().removeIf(entry -> {
            prune(entry.getValue(), now);
            return entry.getValue().isEmpty();
        });
    }

    private Duration waitFor(Deque<Instant> history, Window window, Instant now) {
        Instant boundary = now.minus(window.period());
        long inWindow = history.stream().filter(t -> t.isAfter(boundary)).count();
        if (inWindow < window.permits()) {
            return Duration.ZERO;
        }
        // The window frees up when the oldest entry inside it falls out.
        Instant oldestInWindow = history.stream()
                .filter(t -> t.isAfter(boundary))
                .findFirst()
                .orElse(now);
        return Duration.between(now, oldestInWindow.plus(window.period()));
    }

    private void prune(Deque<Instant> history, Instant now) {
        Instant cutoff = now.minus(longWindow.period());
        while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
            history.removeFirst();
        }
    }

    public record Window(int permits, Duration period) {
        public Window {
            if (permits < 1) throw new IllegalArgumentException("permits must be positive");
            Objects.requireNonNull(period, "period");
        }
    }
}
