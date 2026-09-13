package dev.jvault.outbox;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.gateway.JiraWriteGateway.JiraWriteResult;
import dev.jvault.outbox.backoff.BackoffPolicy;
import dev.jvault.outbox.ratelimit.PerIssueRateLimiter;
import dev.jvault.outbox.support.FixedRandom;
import dev.jvault.outbox.support.InMemoryOutboxRepository;
import dev.jvault.outbox.support.RecordingTicketStateSink;
import dev.jvault.outbox.support.ScriptedJiraWriteGateway;
import dev.jvault.outbox.support.StubPayloadAssembler;
import dev.jvault.outbox.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatcherTest {

    private static final String TICKET = "01J8ZQK5M3T4X9YV2A0B7CDEFG";
    private static final String IDENTITY = "INTEGRATION:svc-jvault";
    private static final String DEPLOYMENT = "jira-cloud-prod";

    private final TestClock clock = TestClock.at("2026-09-11T09:00:00Z");
    private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository();
    private final ScriptedJiraWriteGateway gateway = new ScriptedJiraWriteGateway();
    private final RecordingTicketStateSink tickets = new RecordingTicketStateSink();
    private final PerIssueRateLimiter rateLimiter = PerIssueRateLimiter.jiraCloudDefaults();
    private StubPayloadAssembler assembler;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        assembler = new StubPayloadAssembler();
        dispatcher = new OutboxDispatcher(repository, assembler, EgressGuard.withDefaults(),
                gateway, rateLimiter, BackoffPolicy.atlassianDefault(), tickets, clock,
                FixedRandom.midpoint());
    }

    @Test
    @DisplayName("a successful create sends once and marks the ticket active")
    void successfulCreate() {
        enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
        gateway.fallback(JiraWriteResult.succeeded("10001", "SEC-4471"));

        DispatchReport report = dispatcher.runOnce(10);

        assertThat(report.count(DispatchReport.Disposition.SENT)).isEqualTo(1);
        assertThat(gateway.sendCount()).isEqualTo(1);
        assertThat(only().state()).isEqualTo(OutboxState.SUCCEEDED);
        assertThat(tickets.active())
                .containsExactly(new RecordingTicketStateSink.Active(TICKET, "10001", "SEC-4471"));
    }

    @Test
    @DisplayName("an entry that fails to assemble is rescheduled, not left claimed")
    void unexpectedAssemblyFailureIsContained() {
        enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
        assembler.throwsUnexpectedly("create-issue");

        DispatchReport report = dispatcher.runOnce(10);

        // Letting this out of the dispatcher ends the pass with everything it claimed still
        // marked CLAIMED — work nobody is doing, in a queue that then looks empty.
        assertThat(report.count(DispatchReport.Disposition.RESCHEDULED_TRANSIENT)).isEqualTo(1);
        assertThat(only().state()).isEqualTo(OutboxState.FAILED);
        assertThat(only().lastErrorCode()).isEqualTo("ASSEMBLY_FAILED");
        assertThat(gateway.sendCount()).isZero();
    }

    @Test
    @DisplayName("one entry failing to assemble does not strand the rest of the batch")
    void oneBadEntryDoesNotStrandTheBatch() {
        enqueue(JiraOperation.CREATE_ISSUE, "bad-one", "ticket:other");
        enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
        assembler.throwsUnexpectedly("bad-one");
        gateway.fallback(JiraWriteResult.succeeded("10001", "SEC-4471"));

        DispatchReport report = dispatcher.runOnce(10);

        // Lanes are independent, so a lane that blew up must not take the others with it.
        assertThat(report.count(DispatchReport.Disposition.SENT)).isEqualTo(1);
        assertThat(repository.all()).noneMatch(entry -> entry.state() == OutboxState.CLAIMED);
    }

    @Test
    @DisplayName("an empty queue does no work")
    void emptyQueue() {
        assertThat(dispatcher.runOnce(10).outcomes()).isEmpty();
        assertThat(gateway.sendCount()).isZero();
    }

    @Nested
    @DisplayName("per-issue lanes")
    class Lanes {

        @Test
        @DisplayName("effects in one lane are sent in the order they were enqueued")
        void laneOrderIsPreserved() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            clock.advance(Duration.ofMillis(1));
            enqueue(JiraOperation.SET_PROPERTY, "prop:jvault.origin", lane());
            clock.advance(Duration.ofMillis(1));
            enqueue(JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", lane());

            DispatchReport report = dispatcher.runOnce(10);

            assertThat(report.outcomes()).extracting(DispatchReport.EntryOutcome::effectKey)
                    .containsExactly("create-issue", "prop:jvault.origin", "remote-link:c1");
        }

        @Test
        @DisplayName("a lane stops at its first failure so later effects cannot overtake it")
        void laneStopsAtFirstFailure() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            clock.advance(Duration.ofMillis(1));
            enqueue(JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", lane());

            gateway.then(JiraWriteResult.retryable("JIRA_503"));

            DispatchReport report = dispatcher.runOnce(10);

            // The link must not be attached to an issue whose creation is still being retried.
            assertThat(gateway.sendCount()).isEqualTo(1);
            assertThat(report.count(DispatchReport.Disposition.RESCHEDULED_TRANSIENT)).isEqualTo(1);
            assertThat(report.count(DispatchReport.Disposition.DEFERRED_RATE_LIMIT)).isEqualTo(1);
            assertThat(entry("remote-link:c1").state()).isEqualTo(OutboxState.PENDING);
        }

        @Test
        @DisplayName("once the issue exists, the rest of the lane follows it")
        void effectsMoveToTheIssueLaneAfterCreate() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", OutboxEntry.pendingLaneFor(TICKET));
            clock.advance(Duration.ofMillis(1));
            enqueue(JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1",
                    OutboxEntry.pendingLaneFor(TICKET));
            gateway.fallback(JiraWriteResult.succeeded("10001", "SEC-4471"));

            dispatcher.runOnce(10);

            // Both effects are enqueued before the issue exists, so both carry the pending lane.
            // The moment the create returns an id the rest of the lane has to follow it —
            // otherwise the very next effect in the same pass targets a null issue, which is a
            // 404 from Jira and an abandoned effect. A live run is what found this.
            assertThat(entry("remote-link:c1").issueLane()).isEqualTo("issue:10001");
            assertThat(gateway.sendCount()).isEqualTo(2);
            assertThat(entry("remote-link:c1").state()).isEqualTo(OutboxState.SUCCEEDED);
        }

        @Test
        @DisplayName("effects enqueued later are moved too, not just the ones in flight")
        void laneMoveIsDurable() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", OutboxEntry.pendingLaneFor(TICKET));
            clock.advance(Duration.ofMillis(1));
            enqueue(JiraOperation.ADD_COMMENT, "comment:p1:v1", OutboxEntry.pendingLaneFor(TICKET));
            gateway.fallback(JiraWriteResult.succeeded("10001", "SEC-4471"));

            dispatcher.runOnce(1);   // only the create is claimed in this pass

            // The move is persisted, so a later pass — or another node — sees the right lane.
            assertThat(entry("comment:p1:v1").issueLane()).isEqualTo("issue:10001");
        }

        @Test
        @DisplayName("a stalled lane does not stall a different issue")
        void lanesAreIndependent() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", "issue:10001");
            clock.advance(Duration.ofMillis(1));
            enqueue(JiraOperation.ADD_COMMENT, "comment:p1:v1", "issue:10002");

            gateway.then(JiraWriteResult.retryable("JIRA_503"))
                    .then(JiraWriteResult.succeeded("10002", "SEC-2"));

            DispatchReport report = dispatcher.runOnce(10);

            assertThat(gateway.sendCount()).isEqualTo(2);
            assertThat(report.count(DispatchReport.Disposition.SENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("the rate limiter defers a lane rather than breaching Jira's per-issue limit")
        void rateLimiterDefersLane() {
            for (int i = 0; i < 20; i++) {
                rateLimiter.record(lane(), clock.instant());
            }
            enqueue(JiraOperation.ADD_COMMENT, "comment:p1:v1", lane());

            DispatchReport report = dispatcher.runOnce(10);

            assertThat(gateway.sendCount()).isZero();
            assertThat(report.count(DispatchReport.Disposition.DEFERRED_RATE_LIMIT)).isEqualTo(1);

            OutboxEntry deferred = only();
            assertThat(deferred.state())
                    .as("a deferral is pacing, not a failure")
                    .isEqualTo(OutboxState.PENDING);
            assertThat(deferred.nextAttemptAt()).isAfter(clock.instant());
            assertThat(tickets.failed()).isEmpty();
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("a 429 reschedules, honours Retry-After, and refunds the attempt")
        void throttleDoesNotConsumeTheAttemptBudget() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            gateway.then(JiraWriteResult.throttled(Duration.ofSeconds(45)));

            DispatchReport report = dispatcher.runOnce(10);

            assertThat(report.count(DispatchReport.Disposition.RESCHEDULED_THROTTLED)).isEqualTo(1);

            OutboxEntry after = only();
            assertThat(after.attempts())
                    .as("Jira said 'later', not 'this is broken'")
                    .isZero();
            assertThat(after.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(45));
            assertThat(tickets.failed()).isEmpty();
        }

        @Test
        @DisplayName("repeated throttling never exhausts the budget")
        void repeatedThrottlingNeverExhausts() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            gateway.fallback(JiraWriteResult.throttled(Duration.ofSeconds(1)));

            for (int i = 0; i < 10; i++) {
                dispatcher.runOnce(10);
                clock.advance(Duration.ofSeconds(5));
            }

            assertThat(only().state()).isNotEqualTo(OutboxState.ABANDONED);
            assertThat(only().attempts()).isZero();
            assertThat(tickets.failed()).isEmpty();
        }

        @Test
        @DisplayName("transient failures retry with backoff, then abandon and fail the ticket")
        void transientFailuresExhaustTheBudget() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            gateway.fallback(JiraWriteResult.retryable("JIRA_503"));

            for (int i = 0; i < 4; i++) {
                dispatcher.runOnce(10);
                clock.advance(Duration.ofMinutes(1));
            }

            assertThat(gateway.sendCount()).isEqualTo(4);
            assertThat(only().state()).isEqualTo(OutboxState.ABANDONED);
            assertThat(tickets.failed())
                    .containsExactly(new RecordingTicketStateSink.Failed(TICKET, "JIRA_503"));
        }

        @Test
        @DisplayName("a rejection is not retried — a 400 will still be a 400")
        void rejectionIsNotRetried() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            gateway.fallback(JiraWriteResult.rejected("JIRA_FIELD_VALIDATION"));

            dispatcher.runOnce(10);
            clock.advance(Duration.ofMinutes(5));
            dispatcher.runOnce(10);

            assertThat(gateway.sendCount()).isEqualTo(1);
            assertThat(only().state()).isEqualTo(OutboxState.ABANDONED);
            assertThat(tickets.failed()).hasSize(1);
        }

        @Test
        @DisplayName("an effect overtaken by events succeeds without calling Jira")
        void inapplicableEffectIsANoOp() {
            enqueue(JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", lane());
            assembler.notApplicable("remote-link:c1");

            DispatchReport report = dispatcher.runOnce(10);

            assertThat(gateway.sendCount()).isZero();
            assertThat(report.count(DispatchReport.Disposition.SKIPPED_NOT_APPLICABLE)).isEqualTo(1);
            assertThat(only().state()).isEqualTo(OutboxState.SUCCEEDED);
        }
    }

    @Nested
    @DisplayName("the egress boundary holds at dispatch time")
    class Egress {

        @Test
        @DisplayName("a payload carrying external content never reaches Jira")
        void leakIsBlockedBeforeTheGateway() {
            String canary = "Credential material: AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENGbPxRfiCY";
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            assembler.classification(Classification.RESTRICTED)
                    .leaksExternalContent("create-issue", SensitiveValue.of(canary, "description"));

            DispatchReport report = dispatcher.runOnce(10);

            assertThat(gateway.sendCount())
                    .as("the guard runs before the gateway, not after")
                    .isZero();
            assertThat(report.count(DispatchReport.Disposition.ABANDONED_EGRESS_VIOLATION)).isEqualTo(1);
            assertThat(only().state()).isEqualTo(OutboxState.ABANDONED);
            assertThat(tickets.failed())
                    .containsExactly(new RecordingTicketStateSink.Failed(TICKET, "EGRESS_VIOLATION"));
        }

        @Test
        @DisplayName("an egress violation is never retried — retrying a leak is still a leak")
        void egressViolationIsNotRetried() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            assembler.classification(Classification.RESTRICTED)
                    .leaksExternalContent("create-issue",
                            SensitiveValue.of("a sufficiently long externally placed narrative", "d"));

            dispatcher.runOnce(10);
            clock.advance(Duration.ofHours(1));
            dispatcher.runOnce(10);

            assertThat(gateway.sendCount()).isZero();
        }

        @Test
        @DisplayName("the recorded error code names the violation, never the content")
        void errorCodeCarriesNoContent() {
            String canary = "externally placed narrative that must never appear anywhere";
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            assembler.leaksExternalContent("create-issue", SensitiveValue.of(canary, "description"));

            dispatcher.runOnce(10);

            assertThat(only().lastErrorCode())
                    .doesNotContain(canary)
                    .contains("EXTERNAL_CONTENT_IN_PAYLOAD");
        }
    }

    @Nested
    @DisplayName("ambiguous creation")
    class Ambiguous {

        @Test
        @DisplayName("an unknown outcome holds the entry and never retries it")
        void ambiguousIsHeldNotRetried() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane(),
                    Map.of("projectKey", "SEC", "correlationId", "corr-1",
                            "expectedSummary", "Ticket " + TICKET));
            gateway.fallback(JiraWriteResult.ambiguous("JIRA_TIMEOUT"));

            DispatchReport report = dispatcher.runOnce(10);
            clock.advance(Duration.ofHours(1));
            dispatcher.runOnce(10);

            assertThat(gateway.sendCount())
                    .as("Jira may already have created the issue; a retry could duplicate it")
                    .isEqualTo(1);
            assertThat(report.count(DispatchReport.Disposition.HELD_AMBIGUOUS)).isEqualTo(1);
            assertThat(only().state()).isEqualTo(OutboxState.IN_FLIGHT);
        }

        @Test
        @DisplayName("the resolver is given everything it needs to decide")
        void ambiguityContextIsCaptured() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane(),
                    Map.of("projectKey", "SEC", "correlationId", "corr-1",
                            "expectedSummary", "Ticket " + TICKET));
            gateway.fallback(JiraWriteResult.ambiguous("JIRA_TIMEOUT"));

            dispatcher.runOnce(10);

            assertThat(tickets.ambiguous()).singleElement().satisfies(ctx -> {
                assertThat(ctx.ticketRef()).isEqualTo(TICKET);
                assertThat(ctx.projectKey()).isEqualTo("SEC");
                assertThat(ctx.correlationId()).isEqualTo("corr-1");
                assertThat(ctx.identityRef()).isEqualTo(IDENTITY);
                assertThat(ctx.attemptStartedAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("held entries are discoverable by the reconciler")
        void heldEntriesAreDiscoverable() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            gateway.fallback(JiraWriteResult.ambiguous("JIRA_TIMEOUT"));
            dispatcher.runOnce(10);

            assertThat(repository.findInFlightOlderThan(clock.instant().plusSeconds(1)))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("replay safety")
    class ReplaySafety {

        @Test
        @DisplayName("enqueuing the same effect twice creates one entry")
        void appendIsIdempotentOnEffectKey() {
            enqueue(JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", lane());
            enqueue(JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1", lane());

            assertThat(repository.all()).hasSize(1);

            dispatcher.runOnce(10);

            assertThat(gateway.sendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a claimed entry is not handed out again")
        void claimDoesNotDoubleHand() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());

            assertThat(repository.claim(10, clock.instant())).hasSize(1);
            assertThat(repository.claim(10, clock.instant()))
                    .as("two dispatchers must never send the same effect")
                    .isEmpty();
        }

        @Test
        @DisplayName("a dispatcher that died mid-claim releases its work")
        void staleClaimsAreReleased() {
            enqueue(JiraOperation.CREATE_ISSUE, "create-issue", lane());
            repository.claim(10, clock.instant());

            assertThat(repository.releaseStaleClaims(clock.instant().plusSeconds(60))).isEqualTo(1);
            assertThat(only().state()).isEqualTo(OutboxState.PENDING);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static String lane() {
        return "issue:10001";
    }

    private void enqueue(JiraOperation operation, String effectKey, String lane) {
        enqueue(operation, effectKey, lane, Map.of("projectKey", "SEC"));
    }

    private void enqueue(JiraOperation operation, String effectKey, String lane,
                         Map<String, String> payloadRef) {
        repository.append(OutboxEntry.pending(TICKET, DEPLOYMENT, lane, operation, effectKey,
                payloadRef, IDENTITY, clock.instant()));
    }

    private OutboxEntry only() {
        assertThat(repository.all()).hasSize(1);
        return repository.all().get(0);
    }

    private OutboxEntry entry(String effectKey) {
        return repository.findByEffect(TICKET, effectKey).orElseThrow();
    }
}
