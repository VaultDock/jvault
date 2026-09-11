package dev.jvault.outbox.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jira Cloud allows 20 writes per 2 s and 100 per 30 s against one issue. Creating a ticket
 * performs several writes against a single issue, so this is the limit that actually binds.
 */
class PerIssueRateLimiterTest {

    private static final String LANE = "issue:10001";
    private static final Instant T0 = Instant.parse("2026-09-11T09:00:00Z");

    private final PerIssueRateLimiter limiter = PerIssueRateLimiter.jiraCloudDefaults();

    @Test
    @DisplayName("an unused lane permits immediately")
    void freshLaneIsPermitted() {
        assertThat(limiter.timeUntilPermitted(LANE, T0)).isEmpty();
    }

    @Test
    @DisplayName("the first 20 writes in 2 seconds are permitted, the 21st is not")
    void shortWindowLimit() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.timeUntilPermitted(LANE, T0)).as("write %d", i).isEmpty();
            limiter.record(LANE, T0);
        }

        assertThat(limiter.timeUntilPermitted(LANE, T0)).isPresent();
    }

    @Test
    @DisplayName("the window slides — capacity returns as old writes age out")
    void windowSlides() {
        for (int i = 0; i < 20; i++) {
            limiter.record(LANE, T0);
        }
        assertThat(limiter.timeUntilPermitted(LANE, T0)).isPresent();

        // Just before the window closes, still blocked.
        assertThat(limiter.timeUntilPermitted(LANE, T0.plusMillis(1999))).isPresent();
        // Once the oldest write is 2 s old, capacity is back.
        assertThat(limiter.timeUntilPermitted(LANE, T0.plusSeconds(2))).isEmpty();
    }

    @Test
    @DisplayName("a fixed window would allow 40 writes in a moment; a sliding one does not")
    void slidingWindowPreventsBoundaryBurst() {
        // 20 writes at the end of the first notional window.
        for (int i = 0; i < 20; i++) {
            limiter.record(LANE, T0.plusMillis(1900));
        }

        // A fixed 2 s window starting at T0 would reset at T0+2s and allow 20 more immediately.
        assertThat(limiter.timeUntilPermitted(LANE, T0.plusMillis(2000))).isPresent();
        assertThat(limiter.timeUntilPermitted(LANE, T0.plusMillis(3899))).isPresent();
        assertThat(limiter.timeUntilPermitted(LANE, T0.plusMillis(3900))).isEmpty();
    }

    @Test
    @DisplayName("the longer window binds when writes are spread out")
    void longWindowLimit() {
        // 100 writes spread across 25 s: never 20 in any 2 s, but 100 in under 30 s.
        for (int i = 0; i < 100; i++) {
            limiter.record(LANE, T0.plusMillis(i * 250L));
        }
        Instant justAfter = T0.plusMillis(100 * 250L);

        Optional<Duration> wait = limiter.timeUntilPermitted(LANE, justAfter);

        assertThat(wait).isPresent();
        assertThat(wait.get()).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("lanes are independent — one busy issue does not throttle another")
    void lanesAreIndependent() {
        for (int i = 0; i < 20; i++) {
            limiter.record(LANE, T0);
        }

        assertThat(limiter.timeUntilPermitted(LANE, T0)).isPresent();
        assertThat(limiter.timeUntilPermitted("issue:10002", T0)).isEmpty();
    }

    @Test
    @DisplayName("the reported wait is long enough to actually clear the limit")
    void reportedWaitIsSufficient() {
        for (int i = 0; i < 20; i++) {
            limiter.record(LANE, T0);
        }

        Duration wait = limiter.timeUntilPermitted(LANE, T0).orElseThrow();

        assertThat(limiter.timeUntilPermitted(LANE, T0.plus(wait))).isEmpty();
    }

    @Test
    @DisplayName("idle lanes are evicted so the map does not grow without bound")
    void idleLanesAreEvicted() {
        limiter.record("issue:1", T0);
        limiter.record("issue:2", T0);

        limiter.evictIdleLanes(T0.plusSeconds(31));

        // After eviction the lanes are unknown again, which is the same as permitted.
        assertThat(limiter.timeUntilPermitted("issue:1", T0.plusSeconds(31))).isEmpty();
        assertThat(limiter.timeUntilPermitted("issue:2", T0.plusSeconds(31))).isEmpty();
    }
}
