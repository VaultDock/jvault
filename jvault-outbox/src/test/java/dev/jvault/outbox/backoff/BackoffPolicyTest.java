package dev.jvault.outbox.backoff;

import dev.jvault.outbox.support.FixedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffPolicyTest {

    private final BackoffPolicy policy = BackoffPolicy.atlassianDefault();

    @ParameterizedTest(name = "attempt {0} -> {1} ms before jitter")
    @CsvSource({
            "1,  2000",
            "2,  4000",
            "3,  8000",
            "4, 16000",
            "5, 30000",   // capped
            "9, 30000"    // still capped, no overflow
    })
    @DisplayName("delays double up to the cap")
    void exponentialUpToCap(int attempt, long expectedMillis) {
        // A midpoint jitter of 0.5 over [0.7, 1.3] gives a multiplier of exactly 1.0.
        Duration delay = policy.delayFor(attempt, FixedRandom.midpoint());

        assertThat(delay).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @Test
    @DisplayName("a very large attempt count cannot overflow into a negative delay")
    void noOverflowAtExtremeAttemptCounts() {
        for (int attempt : new int[]{30, 31, 32, 33, 64, Integer.MAX_VALUE}) {
            Duration delay = policy.delayFor(attempt, FixedRandom.midpoint());

            assertThat(delay).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(39));
        }
    }

    @Test
    @DisplayName("jitter always stays inside the configured bounds")
    void jitterStaysWithinBounds() {
        var rng = new Random(20260911L);

        for (int i = 0; i < 2000; i++) {
            Duration delay = policy.delayFor(3, rng);

            // 8 s base for attempt 3, multiplied by [0.7, 1.3].
            assertThat(delay).isBetween(Duration.ofMillis(5600), Duration.ofMillis(10400));
        }
    }

    @Test
    @DisplayName("jitter actually varies — a fixed delay would re-form the herd")
    void jitterProducesSpread() {
        var rng = new Random(20260911L);

        long distinct = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> policy.delayFor(3, rng))
                .distinct()
                .count();

        assertThat(distinct).isGreaterThan(100);
    }

    @Test
    @DisplayName("Retry-After is a floor, not a replacement")
    void retryAfterActsAsAFloor() {
        Instant now = Instant.parse("2026-09-11T09:00:00Z");

        // Jira asks for 45 s; our own backoff would have said 2 s. Jira wins.
        Instant longer = policy.nextAttemptAt(now, 1, Duration.ofSeconds(45), FixedRandom.midpoint());
        assertThat(longer).isEqualTo(now.plusSeconds(45));

        // Jira asks for 1 s; our fourth-attempt backoff says 16 s. We wait the longer time,
        // because Retry-After reflects Jira's load, not how broken this operation is.
        Instant ours = policy.nextAttemptAt(now, 4, Duration.ofSeconds(1), FixedRandom.midpoint());
        assertThat(ours).isEqualTo(now.plusSeconds(16));
    }

    @Test
    @DisplayName("a null Retry-After just uses the computed backoff")
    void nullRetryAfterIsFine() {
        Instant now = Instant.parse("2026-09-11T09:00:00Z");

        assertThat(policy.nextAttemptAt(now, 2, null, FixedRandom.midpoint()))
                .isEqualTo(now.plusSeconds(4));
    }

    @Test
    @DisplayName("the attempt budget runs out after maxAttempts")
    void attemptBudget() {
        assertThat(policy.hasAttemptsLeft(0)).isTrue();
        assertThat(policy.hasAttemptsLeft(3)).isTrue();
        assertThat(policy.hasAttemptsLeft(4)).isFalse();
        assertThat(policy.hasAttemptsLeft(99)).isFalse();
    }

    @Test
    @DisplayName("the defaults are the ones Atlassian documents")
    void defaultsMatchAtlassianGuidance() {
        assertThat(policy.base()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.cap()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.jitterMin()).isEqualTo(0.7);
        assertThat(policy.jitterMax()).isEqualTo(1.3);
        assertThat(policy.maxAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("nonsensical configuration is rejected at construction")
    void invalidConfigurationRejected() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), 0.7, 1.3, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1), 0.7, 1.3, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5), 1.5, 1.3, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5), 0.7, 1.3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("attempt numbering is 1-based and says so")
    void attemptIsOneBased() {
        assertThatThrownBy(() -> policy.delayFor(0, FixedRandom.midpoint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }
}
