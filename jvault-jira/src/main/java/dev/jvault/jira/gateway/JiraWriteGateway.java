package dev.jvault.jira.gateway;

import dev.jvault.jira.egress.JiraSafePayload;

import java.time.Duration;
import java.util.Optional;

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
     * @param issueId    Jira issue id when the operation created or targeted one
     * @param issueKey   Jira issue key, e.g. {@code SEC-4471}
     * @param outcome    what happened, so the outbox can decide whether to retry
     * @param errorCode  stable error class, never error text — the outbox records this and it
     *                   reaches logs and the ingestion status API
     * @param retryAfter Jira's {@code Retry-After}, honoured as a floor on the next attempt
     */
    record JiraWriteResult(String issueId,
                           String issueKey,
                           Outcome outcome,
                           String errorCode,
                           Duration retryAfter) {

        public enum Outcome {
            /** Jira confirmed the write. */
            SUCCEEDED,

            /**
             * Jira rejected it for a reason retrying will not fix — field validation, a missing
             * permission, an unknown project. Dead-letter it; a retry loop against a 400 just
             * burns rate-limit budget that user-facing work needs.
             */
            REJECTED,

            /**
             * HTTP 429. Retryable, but deliberately distinguished from {@link #RETRYABLE}: a
             * throttle is Jira saying "later", not "something went wrong", so it must not
             * consume the error-attempt budget that exists to stop a genuinely broken operation
             * retrying forever (docs/01-requirements.md FR-KAF-5 AC1).
             */
            THROTTLED,

            /** Transient: 5xx, connection failure, a timeout before the request was sent. */
            RETRYABLE,

            /**
             * The outcome is genuinely unknown — typically a timeout after the request left.
             * Jira may or may not have applied it. The ambiguity protocol takes over
             * (docs/12-reliability.md 12.4.2); the caller must not blindly retry.
             */
            AMBIGUOUS
        }

        public static JiraWriteResult succeeded(String issueId, String issueKey) {
            return new JiraWriteResult(issueId, issueKey, Outcome.SUCCEEDED, null, null);
        }

        public static JiraWriteResult rejected(String errorCode) {
            return new JiraWriteResult(null, null, Outcome.REJECTED, errorCode, null);
        }

        public static JiraWriteResult throttled(Duration retryAfter) {
            return new JiraWriteResult(null, null, Outcome.THROTTLED, "JIRA_RATE_LIMITED", retryAfter);
        }

        public static JiraWriteResult retryable(String errorCode) {
            return new JiraWriteResult(null, null, Outcome.RETRYABLE, errorCode, null);
        }

        public static JiraWriteResult ambiguous(String errorCode) {
            return new JiraWriteResult(null, null, Outcome.AMBIGUOUS, errorCode, null);
        }

        public Optional<Duration> retryAfterDuration() {
            return Optional.ofNullable(retryAfter);
        }
    }
}
