package dev.jvault.outbox.ambiguity;

import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxState;
import dev.jvault.outbox.TicketStateSink;
import dev.jvault.outbox.support.FakeJiraIssueSearch;
import dev.jvault.outbox.support.InMemoryOutboxRepository;
import dev.jvault.outbox.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker that runs the ambiguity protocol.
 *
 * <p>{@code AmbiguityResolver} decides and is tested separately; these cover what the reconciler
 * does with each decision, and the ways it must refuse to act.
 */
class AmbiguityReconcilerTest {

    private static final String TICKET = "ticket-1";
    private static final String CORRELATION = "corr-8f21c";
    private static final String SUMMARY = "[HIGH] Unexpected outbound connection";
    private static final Instant ATTEMPT = Instant.parse("2026-09-13T09:41:12Z");

    private final TestClock clock = TestClock.at("2026-09-13T09:44:00Z");
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final FakeJiraIssueSearch search = new FakeJiraIssueSearch();
    private final RecordingSink tickets = new RecordingSink();
    private final CountingSweeps sweeps = new CountingSweeps();

    private AmbiguityReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new AmbiguityReconciler(outbox,
                AmbiguityResolver.withDefaults(search, clock), tickets,
                AmbiguityReconciler.AmbiguityContextSource.fromPayloadRef(), sweeps, clock,
                Duration.ofMinutes(1));
    }

    @Nested
    @DisplayName("adopting an issue that turned out to exist")
    class Adoption {

        @Test
        @DisplayName("a correlation match closes the create and activates the ticket")
        void adoptsOnCorrelationMatch() {
            heldCreate();
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT.plusSeconds(1))
                    .withOrigin("10001", TICKET, CORRELATION);

            var report = reconciler.sweep(10);

            assertThat(report.count("ADOPTED")).isEqualTo(1);
            assertThat(entry().state()).isEqualTo(OutboxState.SUCCEEDED);
            assertThat(tickets.active).containsEntry(TICKET, "SEC-4471");
        }

        @Test
        @DisplayName("everything else for the ticket follows the adopted issue onto its lane")
        void remainingEffectsFollowTheIssue() {
            heldCreate();
            outbox.append(OutboxEntry.pending(TICKET, "dep", OutboxEntry.pendingLaneFor(TICKET),
                    JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", Map.of(), "id",
                    clock.instant()));
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT.plusSeconds(1))
                    .withOrigin("10001", TICKET, CORRELATION);

            reconciler.sweep(10);

            // Otherwise the link would be dispatched against a null issue, which is the bug the
            // live run caught the first time round.
            assertThat(outbox.findByEffect(TICKET, "remote-link:c1").orElseThrow().issueLane())
                    .isEqualTo("issue:10001");
        }

        @Test
        @DisplayName("a heuristic adoption is flagged for a human to confirm")
        void heuristicAdoptionIsFlagged() {
            heldCreate();
            // The create landed but the property did not: only the summary identifies it.
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT.plusSeconds(1));

            var report = reconciler.sweep(10);

            assertThat(report.count("ADOPTED_HEURISTICALLY")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the cases where retrying would be wrong")
    class RefusingToRetry {

        @Test
        @DisplayName("an inconclusive sweep leaves the entry held rather than retrying")
        void inconclusiveHolds() {
            heldCreate();

            var report = reconciler.sweep(10);

            // Nothing found yet is not the same as nothing created; Jira's index lags a create.
            assertThat(report.count("INCONCLUSIVE")).isEqualTo(1);
            assertThat(entry().state()).isEqualTo(OutboxState.IN_FLIGHT);
        }

        @Test
        @DisplayName("a failed search holds too — it means 'cannot tell', not 'not created'")
        void searchFailureHolds() {
            heldCreate();
            search.failWith(new IllegalStateException("Jira search unavailable"));

            var report = reconciler.sweep(10);

            assertThat(report.count("SEARCH_FAILED")).isEqualTo(1);
            assertThat(entry().state()).isEqualTo(OutboxState.IN_FLIGHT);
            assertThat(tickets.failed).isEmpty();
        }

        @Test
        @DisplayName("indistinguishable candidates go to an operator, not to a guess")
        void ambiguousCandidatesEscalate() {
            heldCreate();
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT)
                    .withIssue("10002", "SEC-4472", SUMMARY, ATTEMPT.plusSeconds(1));

            var report = reconciler.sweep(10);

            assertThat(report.count("NEEDS_OPERATOR")).isEqualTo(1);
            assertThat(tickets.failed).containsKey(TICKET);
            assertThat(entry().state())
                    .as("still held: a human decides, and until then nothing is retried")
                    .isEqualTo(OutboxState.IN_FLIGHT);
        }

        @Test
        @DisplayName("an entry younger than the settle delay is not swept at all")
        void settleDelayIsRespected() {
            heldCreate();
            // Sweeping the instant a request times out reliably finds nothing, because Jira's
            // search index is not updated synchronously with the create.
            var impatient = new AmbiguityReconciler(outbox,
                    AmbiguityResolver.withDefaults(search, clock), tickets,
                    AmbiguityReconciler.AmbiguityContextSource.fromPayloadRef(), sweeps, clock,
                    Duration.ofHours(1));

            assertThat(impatient.sweep(10).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("concluding the create never happened")
    class NotCreated {

        @Test
        @DisplayName("after the sweep budget, an empty result releases the create for retry")
        void releasesForRetryAfterBudget() {
            heldCreate();
            sweeps.counts.put(TICKET, 2);

            var report = reconciler.sweep(10);

            // The only resolution that permits a retry.
            assertThat(report.count("RETRY_CREATE")).isEqualTo(1);
            assertThat(entry().state()).isEqualTo(OutboxState.PENDING);
            assertThat(sweeps.counts).doesNotContainKey(TICKET);
        }
    }

    @Nested
    @DisplayName("entries that are not creates")
    class NonCreates {

        @Test
        @DisplayName("an idempotent effect is released instead of being put through the protocol")
        void idempotentEffectsAreJustReleased() {
            outbox.append(OutboxEntry.pending(TICKET, "dep", "issue:10001",
                            JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", Map.of(), "id",
                            clock.instant())
                    .startingAttempt(ATTEMPT, true));

            var report = reconciler.sweep(10);

            // Its outcome being unknown was never ambiguous in the sense this protocol addresses:
            // a globalId upsert reaches the same end state however many times it runs.
            assertThat(report.count("RELEASED_IDEMPOTENT")).isEqualTo(1);
            assertThat(outbox.findByEffect(TICKET, "remote-link:c1").orElseThrow().state())
                    .isEqualTo(OutboxState.PENDING);
        }

        @Test
        @DisplayName("a create with no recoverable context is reported rather than guessed at")
        void missingContextIsReported() {
            outbox.append(OutboxEntry.pending(TICKET, "dep", OutboxEntry.pendingLaneFor(TICKET),
                            JiraOperation.CREATE_ISSUE, "create-issue", Map.of(), "id",
                            clock.instant())
                    .startingAttempt(ATTEMPT, true));

            assertThat(reconciler.sweep(10).count("NO_CONTEXT")).isEqualTo(1);
        }
    }

    // --- fixtures ----------------------------------------------------------------

    private void heldCreate() {
        outbox.append(OutboxEntry.pending(TICKET, "dep", OutboxEntry.pendingLaneFor(TICKET),
                        JiraOperation.CREATE_ISSUE, "create-issue",
                        Map.of("projectKey", "SEC", "correlationId", CORRELATION,
                                "expectedSummary", SUMMARY),
                        "INTEGRATION:svc", clock.instant())
                .startingAttempt(ATTEMPT, true));
    }

    private OutboxEntry entry() {
        return outbox.findByEffect(TICKET, "create-issue").orElseThrow();
    }

    private static final class RecordingSink implements TicketStateSink {
        final Map<String, String> active = new HashMap<>();
        final Map<String, String> failed = new HashMap<>();

        @Override
        public void ticketBecameActive(String ticketRef, String issueId, String issueKey,
                                       Instant when) {
            active.put(ticketRef, issueKey);
        }

        @Override
        public void ticketBecameAmbiguous(String ticketRef, AmbiguityContext context) {
        }

        @Override
        public void ticketFailed(String ticketRef, String errorCode, Instant when) {
            failed.put(ticketRef, errorCode);
        }
    }

    private static final class CountingSweeps implements AmbiguityReconciler.SweepCounter {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public int sweepsFor(String ticketRef) {
            return counts.getOrDefault(ticketRef, 0);
        }

        @Override
        public void recordSweep(String ticketRef) {
            counts.merge(ticketRef, 1, Integer::sum);
        }

        @Override
        public void clear(String ticketRef) {
            counts.remove(ticketRef);
        }
    }
}
