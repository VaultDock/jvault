package dev.jvault.outbox;

/**
 * Lifecycle of one pending Jira mutation. Mirrors {@code jira_outbox.state}
 * (docs/04-data-model.md 4.3).
 */
public enum OutboxState {

    /** Waiting for its turn. Eligible for claiming once {@code nextAttemptAt} has passed. */
    PENDING,

    /** Claimed by a dispatcher, not yet sent. Equivalent to holding the row lock. */
    CLAIMED,

    /**
     * Sent to Jira, outcome not yet recorded.
     *
     * <p>An entry left here after an {@code AMBIGUOUS} result is <em>held</em> deliberately: the
     * dispatcher must not retry it, because Jira may already have applied it. The ambiguity
     * resolver owns it from that point (docs/12-reliability.md 12.4.2).
     */
    IN_FLIGHT,

    /** Jira confirmed it. Terminal. */
    SUCCEEDED,

    /** Failed transiently; rescheduled. Still eligible for another attempt. */
    FAILED,

    /** Out of attempts, or rejected for a reason retrying cannot fix. Terminal, alerts. */
    ABANDONED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == ABANDONED;
    }

    /** Whether the dispatcher may pick this entry up. */
    public boolean isClaimable() {
        return this == PENDING || this == FAILED;
    }
}
