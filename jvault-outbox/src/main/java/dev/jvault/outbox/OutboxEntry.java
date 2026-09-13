package dev.jvault.outbox;

import dev.jvault.jira.egress.JiraOperation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One pending Jira mutation.
 *
 * <p><strong>{@code payloadRef} holds identifiers only — never content.</strong> The dispatcher
 * re-reads the actual values at execution time and runs them through the egress guard. Two
 * reasons, both load-bearing: this table gets dumped into support tickets and diagnostics, and
 * re-reading guarantees the guard examines the bytes that are actually sent rather than a copy
 * taken earlier (docs/04-data-model.md 4.3).
 *
 * @param effectKey deterministic per (ticket, effect), which is what makes replay a no-op.
 *                  Examples: {@code create-issue}, {@code remote-link:{contentRef}},
 *                  {@code comment:{partId}:v{versionNo}}.
 */
public record OutboxEntry(
        UUID id,
        String ticketRef,
        String deploymentId,
        String issueLane,
        JiraOperation operation,
        String effectKey,
        Map<String, String> payloadRef,
        String identityRef,
        OutboxState state,
        int attempts,
        Instant nextAttemptAt,
        String lastErrorCode,
        Instant attemptStartedAt,
        Instant createdAt) {

    public OutboxEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ticketRef, "ticketRef");
        Objects.requireNonNull(issueLane, "issueLane");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(effectKey, "effectKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        payloadRef = payloadRef == null ? Map.of() : Map.copyOf(payloadRef);
    }

    /** The lane an entry belongs to before the Jira issue exists. */
    public static String pendingLaneFor(String ticketRef) {
        return "ticket:" + ticketRef;
    }

    public static OutboxEntry pending(String ticketRef,
                                      String deploymentId,
                                      String issueLane,
                                      JiraOperation operation,
                                      String effectKey,
                                      Map<String, String> payloadRef,
                                      String identityRef,
                                      Instant now) {
        return new OutboxEntry(UUID.randomUUID(), ticketRef, deploymentId, issueLane, operation,
                effectKey, payloadRef, identityRef, OutboxState.PENDING, 0, now, null, null, now);
    }

    /**
     * Moves this entry to a different serialisation lane.
     *
     * <p>Used when a ticket's issue comes into existence: effects enqueued before the create
     * succeeded carry the pending lane {@code ticket:<ref>}, and from that moment on they belong
     * to {@code issue:<id>} — which is both the correct rate-limit bucket and the only way the
     * gateway can tell which issue to target.
     */
    public OutboxEntry withIssueLane(String newLane) {
        return new OutboxEntry(id, ticketRef, deploymentId, newLane, operation, effectKey,
                payloadRef, identityRef, state, attempts, nextAttemptAt, lastErrorCode,
                attemptStartedAt, createdAt);
    }

    public OutboxEntry withState(OutboxState newState) {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, newState, attempts, nextAttemptAt, lastErrorCode,
                attemptStartedAt, createdAt);
    }

    public OutboxEntry startingAttempt(Instant now, boolean consumesAttempt) {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, OutboxState.IN_FLIGHT,
                consumesAttempt ? attempts + 1 : attempts,
                nextAttemptAt, lastErrorCode, now, createdAt);
    }

    /**
     * Gives back the attempt consumed by a try that Jira throttled.
     *
     * <p>Attempts are consumed at the <em>start</em> of a try, so that a dispatcher crashing
     * mid-flight still counts against the budget and a poisoned effect cannot retry for ever.
     * A throttle is the one case where that is the wrong accounting: Jira said "later", not
     * "this is broken", so the attempt is refunded (FR-KAF-5 AC1).
     */
    public OutboxEntry withAttemptsRefunded() {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, state, Math.max(0, attempts - 1), nextAttemptAt,
                lastErrorCode, attemptStartedAt, createdAt);
    }

    public OutboxEntry rescheduled(Instant when, String errorCode) {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, OutboxState.FAILED, attempts, when, errorCode,
                attemptStartedAt, createdAt);
    }

    /**
     * Puts the entry back in the queue for later, without recording a failure.
     *
     * <p>Deliberately {@link OutboxState#PENDING} rather than {@link OutboxState#FAILED}: being
     * held back by the per-issue rate limiter is normal pacing, not an error, and a dashboard
     * that shows it as a failure trains operators to ignore real ones.
     */
    public OutboxEntry deferredUntil(Instant when, String reasonCode) {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, OutboxState.PENDING, attempts, when, reasonCode,
                attemptStartedAt, createdAt);
    }

    public OutboxEntry succeeded() {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, OutboxState.SUCCEEDED, attempts, nextAttemptAt, null,
                attemptStartedAt, createdAt);
    }

    public OutboxEntry abandoned(String errorCode) {
        return new OutboxEntry(id, ticketRef, deploymentId, issueLane, operation, effectKey,
                payloadRef, identityRef, OutboxState.ABANDONED, attempts, nextAttemptAt, errorCode,
                attemptStartedAt, createdAt);
    }

    /** Identifiers only, by construction — so this is always safe to log. */
    @Override
    public String toString() {
        return "OutboxEntry[" + operation + ", ticket=" + ticketRef + ", effect=" + effectKey
                + ", lane=" + issueLane + ", state=" + state + ", attempts=" + attempts + "]";
    }
}
