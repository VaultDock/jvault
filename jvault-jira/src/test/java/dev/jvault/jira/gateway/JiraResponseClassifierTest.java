package dev.jvault.jira.gateway;

import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.gateway.JiraWriteGateway.JiraWriteResult;
import dev.jvault.jira.gateway.JiraWriteGateway.JiraWriteResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry policy, which is expensive to get wrong in both directions: too eager and a 400 burns
 * rate-limit budget user-facing work needs, too cautious and a transient 503 dead-letters a ticket
 * that would have succeeded a second later.
 */
class JiraResponseClassifierTest {

    @Nested
    @DisplayName("responses")
    class Responses {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "200, SUCCEEDED",
                "201, SUCCEEDED",
                "204, SUCCEEDED",
                "400, REJECTED",
                "401, REJECTED",
                "403, REJECTED",
                "404, REJECTED",
                "413, REJECTED",
                "415, REJECTED",
                "429, THROTTLED",
                "500, RETRYABLE",
                "502, RETRYABLE",
                "503, RETRYABLE",
                "504, RETRYABLE",
        })
        @DisplayName("status codes map to the outcome the outbox should act on")
        void statusMapping(int status, Outcome expected) {
            assertThat(JiraResponseClassifier.classify(status, Map.of(), null, null).outcome())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a rejection names its cause, so an operator sees why without the payload")
        void rejectionsCarryACode() {
            assertThat(JiraResponseClassifier.classify(400, Map.of(), null, null).errorCode())
                    .isEqualTo("JIRA_FIELD_VALIDATION");
            assertThat(JiraResponseClassifier.classify(401, Map.of(), null, null).errorCode())
                    .isEqualTo("JIRA_UNAUTHENTICATED");
            assertThat(JiraResponseClassifier.classify(413, Map.of(), null, null).errorCode())
                    .isEqualTo("JIRA_PAYLOAD_TOO_LARGE");
        }

        @Test
        @DisplayName("an expired credential is rejected, not retried")
        void authFailuresAreNotRetried() {
            // Retrying a 401 cannot fix it, and a loop of them looks like a brute-force attempt.
            // The connection needs reconnecting instead.
            assertThat(JiraResponseClassifier.classify(401, Map.of(), null, null).outcome())
                    .isEqualTo(Outcome.REJECTED);
        }

        @Test
        @DisplayName("success carries the issue Jira created")
        void successCarriesTheIssue() {
            JiraWriteResult result =
                    JiraResponseClassifier.classify(201, Map.of(), "10001", "SEC-4471");

            assertThat(result.issueId()).isEqualTo("10001");
            assertThat(result.issueKey()).isEqualTo("SEC-4471");
        }

        @Test
        @DisplayName("an unmapped 4xx is rejected and an unmapped 5xx is retried")
        void unmappedStatusesFallBackSensibly() {
            assertThat(JiraResponseClassifier.classify(418, Map.of(), null, null).outcome())
                    .isEqualTo(Outcome.REJECTED);
            assertThat(JiraResponseClassifier.classify(599, Map.of(), null, null).outcome())
                    .isEqualTo(Outcome.RETRYABLE);
        }
    }

    @Nested
    @DisplayName("rate limiting")
    class RateLimiting {

        @Test
        @DisplayName("Retry-After in seconds is honoured")
        void retryAfterSeconds() {
            JiraWriteResult result = JiraResponseClassifier.classify(
                    429, Map.of("Retry-After", "45"), null, null);

            assertThat(result.outcome()).isEqualTo(Outcome.THROTTLED);
            assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("Retry-After as an HTTP date is honoured too")
        void retryAfterHttpDate() {
            String date = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);

            Duration delay = JiraResponseClassifier.classify(
                    429, Map.of("Retry-After", date), null, null).retryAfter();

            // RFC 9110 permits both forms and both appear in the wild.
            assertThat(delay).isBetween(Duration.ofSeconds(100), Duration.ofSeconds(125));
        }

        @Test
        @DisplayName("header names match case-insensitively, as HTTP requires")
        void headerLookupIsCaseInsensitive() {
            assertThat(JiraResponseClassifier.classify(
                    429, Map.of("retry-after", "30"), null, null).retryAfter())
                    .isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("an unparseable Retry-After falls back rather than throwing")
        void malformedRetryAfterIsIgnored() {
            // This path is already handling a failure; throwing here would turn a throttle into
            // an unhandled error.
            JiraWriteResult result = JiraResponseClassifier.classify(
                    429, Map.of("Retry-After", "soon please"), null, null);

            assertThat(result.outcome()).isEqualTo(Outcome.THROTTLED);
            assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("the near-limit warning is readable, so we can slow down before being told to")
        void nearLimitWarning() {
            assertThat(JiraResponseClassifier.nearRateLimit(Map.of("X-RateLimit-NearLimit", "true")))
                    .isTrue();
            assertThat(JiraResponseClassifier.nearRateLimit(Map.of())).isFalse();
        }

        @Test
        @DisplayName("which of Jira's three limits fired is recoverable")
        void rateLimitReasonIsAvailable() {
            // Points, burst and per-issue behave differently and need different responses;
            // conflating them makes tuning guesswork.
            assertThat(JiraResponseClassifier.rateLimitReason(
                    Map.of("RateLimit-Reason", "jira-quota-global-based")))
                    .contains("jira-quota-global-based");
        }
    }

    @Nested
    @DisplayName("transport failures — where ambiguity is decided")
    class TransportFailures {

        @Test
        @DisplayName("a connection that was never established is plainly retryable")
        void neverReachedJiraIsRetryable() {
            for (Throwable failure : new Throwable[]{
                    new ConnectException("refused"),
                    new UnknownHostException("nope"),
                    new IOException("something")}) {

                JiraWriteResult result = JiraResponseClassifier.classifyTransportFailure(
                        failure, false, JiraOperation.CREATE_ISSUE);

                assertThat(result.outcome())
                        .as("%s", failure.getClass().getSimpleName())
                        .isEqualTo(Outcome.RETRYABLE);
            }
        }

        @Test
        @DisplayName("a timeout after sending a create is ambiguous, never retried blindly")
        void timeoutOnCreateIsAmbiguous() {
            JiraWriteResult result = JiraResponseClassifier.classifyTransportFailure(
                    new HttpTimeoutException("read timed out"), true, JiraOperation.CREATE_ISSUE);

            // Jira may already have created the issue. Retrying could duplicate a live ticket,
            // so the ambiguity protocol takes over instead.
            assertThat(result.outcome()).isEqualTo(Outcome.AMBIGUOUS);
            assertThat(result.errorCode()).isEqualTo("JIRA_TIMEOUT");
        }

        @ParameterizedTest
        @EnumSource(value = JiraOperation.class,
                names = {"UPSERT_REMOTE_LINK", "SET_PROPERTY", "UPDATE_FIELDS",
                        "DELETE_COMMENT", "DELETE_REMOTE_LINK", "DELETE_ATTACHMENT", "EDIT_COMMENT"})
        @DisplayName("the same timeout on an idempotent operation is merely retryable")
        void timeoutOnIdempotentOperationIsRetryable(JiraOperation operation) {
            JiraWriteResult result = JiraResponseClassifier.classifyTransportFailure(
                    new HttpTimeoutException("read timed out"), true, operation);

            // Unknown, but harmless to repeat: a globalId upsert deduplicates and a property
            // write is a PUT, so the end state is the same either way. Keeping these out of the
            // ambiguity protocol is what stops it becoming routine.
            assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE);
        }

        @ParameterizedTest
        @EnumSource(value = JiraOperation.class, names = {"CREATE_ISSUE", "ADD_COMMENT", "ADD_ATTACHMENT"})
        @DisplayName("every non-idempotent operation goes ambiguous on an unknown outcome")
        void nonIdempotentOperationsGoAmbiguous(JiraOperation operation) {
            assertThat(operation.isIdempotent()).isFalse();
            assertThat(JiraResponseClassifier.classifyTransportFailure(
                    new IOException("reset"), true, operation).outcome())
                    .isEqualTo(Outcome.AMBIGUOUS);
        }

        @Test
        @DisplayName("failures are named by class, without leaking the request")
        void failureCodesAreStable() {
            assertThat(JiraResponseClassifier.classifyTransportFailure(
                    new ConnectException("x"), false, JiraOperation.CREATE_ISSUE).errorCode())
                    .isEqualTo("JIRA_CONNECT_FAILED");
            assertThat(JiraResponseClassifier.classifyTransportFailure(
                    new UnknownHostException("x"), false, JiraOperation.CREATE_ISSUE).errorCode())
                    .isEqualTo("JIRA_UNKNOWN_HOST");
            assertThat(JiraResponseClassifier.classifyTransportFailure(
                    new javax.net.ssl.SSLException("x"), false, JiraOperation.CREATE_ISSUE).errorCode())
                    .isEqualTo("JIRA_TLS_FAILED");
        }
    }
}
