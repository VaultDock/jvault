package dev.jvault.outbox;

import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.egress.EgressViolationException;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.egress.JiraSafePayload;
import dev.jvault.jira.egress.JiraWriteRequest;
import dev.jvault.jira.gateway.JiraWriteGateway;
import dev.jvault.outbox.backoff.BackoffPolicy;
import dev.jvault.outbox.ratelimit.PerIssueRateLimiter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Drains the outbox into Jira.
 *
 * <p>Three properties matter more than throughput:
 *
 * <ol>
 *   <li><strong>Per-issue serialisation.</strong> Entries are grouped by {@code issueLane} and
 *       each lane is processed in order, one at a time. This is what keeps jvault inside Jira's
 *       20-writes-per-2-seconds-per-issue limit however many nodes are running, and it also
 *       preserves ordering — a remote link must not be attached before the issue exists.</li>
 *   <li><strong>Nothing reaches Jira unguarded.</strong> Values are re-read at dispatch time and
 *       pass through {@link EgressGuard}; a violation abandons the entry rather than retrying,
 *       because retrying a leak is still a leak.</li>
 *   <li><strong>Ambiguity is never guessed.</strong> An unknown outcome holds the entry
 *       {@code IN_FLIGHT} and hands the ticket to the ambiguity resolver. The dispatcher does not
 *       retry a creation whose result it does not know.</li>
 * </ol>
 *
 * <p>A lane stops at its first deferral or failure. Continuing past a failed entry would let a
 * later effect land before an earlier one — attaching a link to an issue whose creation is still
 * being retried, for instance.
 */
public final class OutboxDispatcher {

    private final OutboxRepository repository;
    private final JiraPayloadAssembler assembler;
    private final EgressGuard egressGuard;
    private final JiraWriteGateway gateway;
    private final PerIssueRateLimiter rateLimiter;
    private final BackoffPolicy backoff;
    private final TicketStateSink ticketStates;
    private final Clock clock;
    private final RandomGenerator rng;

    public OutboxDispatcher(OutboxRepository repository,
                            JiraPayloadAssembler assembler,
                            EgressGuard egressGuard,
                            JiraWriteGateway gateway,
                            PerIssueRateLimiter rateLimiter,
                            BackoffPolicy backoff,
                            TicketStateSink ticketStates,
                            Clock clock,
                            RandomGenerator rng) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.egressGuard = Objects.requireNonNull(egressGuard, "egressGuard");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.ticketStates = Objects.requireNonNull(ticketStates, "ticketStates");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rng = Objects.requireNonNull(rng, "rng");
    }

    public DispatchReport runOnce(int batchSize) {
        Instant now = clock.instant();
        List<OutboxEntry> claimed = repository.claim(batchSize, now);
        if (claimed.isEmpty()) {
            return DispatchReport.empty();
        }

        var report = new DispatchReport.Builder();

        // Lanes are independent and could run in parallel; entries within a lane must not.
        for (Map.Entry<String, List<OutboxEntry>> lane : groupByLane(claimed).entrySet()) {
            processLane(lane.getValue(), report);
        }

        rateLimiter.evictIdleLanes(clock.instant());
        return report.build();
    }

    private void processLane(List<OutboxEntry> entries, DispatchReport.Builder report) {
        for (int i = 0; i < entries.size(); i++) {
            if (dispatch(entries.get(i), report) == LaneProgress.STOP) {
                // Return the rest of the lane to the queue, in their original order.
                releaseRemaining(entries, i + 1, report);
                return;
            }
        }
    }

    private void releaseRemaining(List<OutboxEntry> entries, int from, DispatchReport.Builder report) {
        for (OutboxEntry deferred : entries.subList(from, entries.size())) {
            // Back to PENDING with their original schedule: the lane stopped, these never ran.
            repository.save(deferred.withState(OutboxState.PENDING));
            report.add(deferred, DispatchReport.Disposition.DEFERRED_RATE_LIMIT, null);
        }
    }

    private LaneProgress dispatch(OutboxEntry entry, DispatchReport.Builder report) {
        Instant now = clock.instant();

        Optional<Duration> wait = rateLimiter.timeUntilPermitted(entry.issueLane(), now);
        if (wait.isPresent()) {
            repository.save(entry.deferredUntil(now.plus(wait.get()), "PER_ISSUE_RATE_LIMIT"));
            report.add(entry, DispatchReport.Disposition.DEFERRED_RATE_LIMIT, null);
            return LaneProgress.STOP;
        }

        JiraWriteRequest request;
        try {
            request = assembler.assemble(entry);
        } catch (JiraPayloadAssembler.EffectNoLongerApplicable e) {
            // The end state already holds. Marking it succeeded is correct, not a fudge: the
            // outbox's job is to reach a desired state, not to force a particular call.
            repository.save(entry.succeeded());
            report.add(entry, DispatchReport.Disposition.SKIPPED_NOT_APPLICABLE, e.reasonCode());
            return LaneProgress.CONTINUE;
        }

        JiraSafePayload payload;
        try {
            payload = egressGuard.sanitise(request);
        } catch (EgressViolationException e) {
            // Never retried. A retry would attempt the same leak, and the codes are already
            // enough for an operator to find the cause.
            String codes = e.violations().stream()
                    .map(v -> v.code() + "@" + v.fieldKey())
                    .distinct()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("EGRESS_VIOLATION");
            repository.save(entry.abandoned(codes));
            ticketStates.ticketFailed(entry.ticketRef(), "EGRESS_VIOLATION", now);
            report.add(entry, DispatchReport.Disposition.ABANDONED_EGRESS_VIOLATION, codes);
            return LaneProgress.STOP;
        }

        OutboxEntry inFlight = entry.startingAttempt(now, true);
        repository.save(inFlight);
        rateLimiter.record(entry.issueLane(), now);

        JiraWriteGateway.JiraWriteResult result = gateway.execute(payload);
        return record(inFlight, result, report);
    }

    private LaneProgress record(OutboxEntry entry,
                                JiraWriteGateway.JiraWriteResult result,
                                DispatchReport.Builder report) {
        Instant now = clock.instant();

        return switch (result.outcome()) {
            case SUCCEEDED -> {
                repository.save(entry.succeeded());
                if (entry.operation() == JiraOperation.CREATE_ISSUE) {
                    ticketStates.ticketBecameActive(
                            entry.ticketRef(), result.issueId(), result.issueKey(), now);
                }
                report.add(entry, DispatchReport.Disposition.SENT, null);
                yield LaneProgress.CONTINUE;
            }

            case THROTTLED -> {
                // A throttle does not consume the error budget, so the attempt is given back.
                OutboxEntry refunded = entry.withAttemptsRefunded();
                // Attempts stay flat across repeated throttles, so the delay does not escalate;
                // Retry-After is what actually paces us.
                repository.save(refunded.rescheduled(
                        backoff.nextAttemptAt(now, Math.max(1, refunded.attempts()),
                                result.retryAfter(), rng),
                        result.errorCode()));
                report.add(entry, DispatchReport.Disposition.RESCHEDULED_THROTTLED, result.errorCode());
                yield LaneProgress.STOP;
            }

            case RETRYABLE -> {
                if (backoff.hasAttemptsLeft(entry.attempts())) {
                    repository.save(entry.rescheduled(
                            backoff.nextAttemptAt(now, entry.attempts(), result.retryAfter(), rng),
                            result.errorCode()));
                    report.add(entry, DispatchReport.Disposition.RESCHEDULED_TRANSIENT, result.errorCode());
                } else {
                    repository.save(entry.abandoned(result.errorCode()));
                    ticketStates.ticketFailed(entry.ticketRef(), result.errorCode(), now);
                    report.add(entry, DispatchReport.Disposition.ABANDONED_EXHAUSTED, result.errorCode());
                }
                yield LaneProgress.STOP;
            }

            case REJECTED -> {
                repository.save(entry.abandoned(result.errorCode()));
                ticketStates.ticketFailed(entry.ticketRef(), result.errorCode(), now);
                report.add(entry, DispatchReport.Disposition.ABANDONED_REJECTED, result.errorCode());
                yield LaneProgress.STOP;
            }

            case AMBIGUOUS -> {
                // Held IN_FLIGHT on purpose: the dispatcher must not retry a creation that may
                // already have taken effect. Ownership passes to the ambiguity resolver.
                repository.save(entry.withState(OutboxState.IN_FLIGHT));
                if (entry.operation() == JiraOperation.CREATE_ISSUE) {
                    ticketStates.ticketBecameAmbiguous(entry.ticketRef(), ambiguityContext(entry));
                }
                report.add(entry, DispatchReport.Disposition.HELD_AMBIGUOUS, result.errorCode());
                yield LaneProgress.STOP;
            }
        };
    }

    private TicketStateSink.AmbiguityContext ambiguityContext(OutboxEntry entry) {
        Map<String, String> ref = entry.payloadRef();
        return new TicketStateSink.AmbiguityContext(
                entry.ticketRef(),
                entry.deploymentId(),
                ref.get("projectKey"),
                entry.identityRef(),
                ref.get("correlationId"),
                ref.get("expectedSummary"),
                entry.attemptStartedAt());
    }

    private static Map<String, List<OutboxEntry>> groupByLane(List<OutboxEntry> entries) {
        var byLane = new LinkedHashMap<String, List<OutboxEntry>>();
        for (OutboxEntry entry : entries) {
            byLane.computeIfAbsent(entry.issueLane(), k -> new java.util.ArrayList<>()).add(entry);
        }
        return byLane;
    }

    private enum LaneProgress {
        CONTINUE,
        /** Stop this lane. Later effects must not overtake an earlier one that has not landed. */
        STOP
    }
}
