package dev.jvault.outbox;

import java.time.Instant;

/**
 * Where the dispatcher reports ticket-level transitions.
 *
 * <p>Separated from {@link OutboxRepository} because these are different concerns with different
 * consistency requirements: the outbox tracks individual effects, while the ticket state machine
 * (docs/04-data-model.md 4.4) tracks whether the ticket as a whole is usable. The production
 * adapter writes both in one transaction; the port keeps the dispatcher from assuming that.
 */
public interface TicketStateSink {

    /** The Jira issue exists and is recorded. */
    void ticketBecameActive(String ticketRef, String issueId, String issueKey, Instant when);

    /**
     * A creation's outcome is unknown. The ticket must not be retried until the ambiguity
     * resolver has decided (docs/12-reliability.md 12.4.2).
     */
    void ticketBecameAmbiguous(String ticketRef, AmbiguityContext context);

    /** Out of attempts, or rejected. The content survives; the ticket goes to an operator queue. */
    void ticketFailed(String ticketRef, String errorCode, Instant when);

    /**
     * Everything the ambiguity resolver needs to decide, captured before the uncertain call was
     * made. Identifiers only.
     *
     * @param attemptStartedAt when the create request left, bounding the search window
     */
    record AmbiguityContext(String ticketRef,
                            String deploymentId,
                            String projectKey,
                            String identityRef,
                            String correlationId,
                            String expectedSummary,
                            Instant attemptStartedAt) {
    }
}
