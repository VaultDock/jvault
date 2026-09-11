package dev.jvault.jira.gateway;

import dev.jvault.jira.egress.JiraSafePayload;

/**
 * The single exit from jvault to Jira for mutations.
 *
 * <p>The signature is the point: it accepts only a {@link JiraSafePayload}, which only
 * {@link dev.jvault.jira.egress.EgressGuard} can construct. There is no overload taking a
 * map, a string, or a raw request — adding one would silently remove the guarantee that every
 * Jira write has been checked.
 */
public interface JiraWriteGateway {

    JiraWriteResult execute(JiraSafePayload payload);

    /**
     * @param issueId  Jira issue id when the operation created or targeted one
     * @param issueKey Jira issue key, e.g. {@code SEC-4471}
     * @param outcome  what happened, so the outbox can decide whether to retry
     */
    record JiraWriteResult(String issueId, String issueKey, Outcome outcome) {

        public enum Outcome {
            /** Jira confirmed the write. */
            SUCCEEDED,
            /** Jira rejected it for a reason retrying will not fix; dead-letter it. */
            REJECTED,
            /** Transient: 429, 5xx, connection failure. Retry with backoff. */
            RETRYABLE,
            /**
             * The outcome is genuinely unknown — a timeout after the request was sent. The
             * ambiguity protocol takes over (docs/12-reliability.md 12.4.2); the caller must
             * not blindly retry.
             */
            AMBIGUOUS
        }
    }
}
