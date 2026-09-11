package dev.jvault.jira.gateway;

import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.gateway.JiraWriteGateway.JiraWriteResult;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a Jira response — or a failure to get one — into an outcome the outbox can act on.
 *
 * <p>Pure, and deliberately so: this is where the retry policy actually lives, and getting it
 * wrong is expensive in both directions. Too eager, and a 400 is retried four times, burning
 * rate-limit budget that user-facing work needs. Too cautious, and a transient 503 dead-letters a
 * ticket that would have succeeded a second later.
 *
 * <p>The subtle case is <strong>ambiguity</strong>. When a request times out after being sent,
 * jvault cannot know whether Jira applied it. But that only matters for operations where
 * repeating would do damage: a remote-link upsert deduplicates on {@code globalId} and a property
 * write is a {@code PUT}, so retrying either reaches the same end state. Creating an issue is the
 * case that matters, because Jira offers no idempotency key for it — so ambiguity is reserved for
 * non-idempotent operations and the ambiguity protocol stays rare rather than routine.
 */
public final class JiraResponseClassifier {

    private JiraResponseClassifier() {
    }

    public static JiraWriteResult classify(int status,
                                           Map<String, String> headers,
                                           String issueId,
                                           String issueKey) {
        if (status >= 200 && status < 300) {
            return JiraWriteResult.succeeded(issueId, issueKey);
        }

        return switch (status) {
            // Jira is asking us to slow down, not telling us anything is wrong. Retry-After is
            // honoured as a floor and the attempt is refunded by the dispatcher.
            case 429 -> JiraWriteResult.throttled(
                    retryAfter(headers).orElse(Duration.ofSeconds(2)));

            // The credential is gone, expired, or revoked. Retrying cannot fix it and will look
            // like a brute-force attempt; the connection needs reconnecting instead.
            case 401 -> JiraWriteResult.rejected("JIRA_UNAUTHENTICATED");
            case 403 -> JiraWriteResult.rejected("JIRA_FORBIDDEN");

            // A missing project, issue type or issue. Not transient.
            case 404 -> JiraWriteResult.rejected("JIRA_NOT_FOUND");

            case 400 -> JiraWriteResult.rejected("JIRA_FIELD_VALIDATION");
            case 413 -> JiraWriteResult.rejected("JIRA_PAYLOAD_TOO_LARGE");
            case 415 -> JiraWriteResult.rejected("JIRA_UNSUPPORTED_MEDIA_TYPE");

            // 5xx is Jira's problem and usually brief.
            case 500, 502, 503, 504 -> JiraWriteResult.retryable("JIRA_" + status);

            default -> status >= 500
                    ? JiraWriteResult.retryable("JIRA_" + status)
                    : JiraWriteResult.rejected("JIRA_" + status);
        };
    }

    /**
     * Classifies a request that produced no response at all.
     *
     * @param requestReachedJira whether the request was actually written to the connection. A
     *                           connection that was never established cannot have had an effect,
     *                           so it is plainly retryable; a read timeout after sending is the
     *                           genuinely unknown case
     */
    public static JiraWriteResult classifyTransportFailure(Throwable failure,
                                                           boolean requestReachedJira,
                                                           JiraOperation operation) {
        String code = failureCode(failure);

        if (!requestReachedJira) {
            return JiraWriteResult.retryable(code);
        }
        if (operation.isIdempotent()) {
            // Unknown, but harmless to repeat: the end state is the same either way.
            return JiraWriteResult.retryable(code);
        }
        // Unknown, and repeating could duplicate. This is what the ambiguity protocol exists for.
        return JiraWriteResult.ambiguous(code);
    }

    private static String failureCode(Throwable failure) {
        if (failure instanceof java.net.http.HttpTimeoutException) {
            return "JIRA_TIMEOUT";
        }
        if (failure instanceof java.net.ConnectException) {
            return "JIRA_CONNECT_FAILED";
        }
        if (failure instanceof java.net.UnknownHostException) {
            return "JIRA_UNKNOWN_HOST";
        }
        if (failure instanceof javax.net.ssl.SSLException) {
            return "JIRA_TLS_FAILED";
        }
        return "JIRA_TRANSPORT_FAILED";
    }

    /**
     * Parses {@code Retry-After}, which RFC 9110 allows to be either a delay in seconds or an
     * HTTP date. Both forms appear in the wild, so both are handled; anything else is ignored
     * rather than allowed to throw on a path that is already handling a failure.
     */
    static Optional<Duration> retryAfter(Map<String, String> headers) {
        String value = headerIgnoringCase(headers, "Retry-After");
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException notASecondsValue) {
            // Fall through to the date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration until = Duration.between(Instant.now(), when.toInstant());
            return until.isNegative() ? Optional.of(Duration.ZERO) : Optional.of(until);
        } catch (RuntimeException notADate) {
            return Optional.empty();
        }
    }

    /** True when Jira is warning that the rate budget is nearly spent, so we can slow down early. */
    public static boolean nearRateLimit(Map<String, String> headers) {
        return "true".equalsIgnoreCase(headerIgnoringCase(headers, "X-RateLimit-NearLimit"));
    }

    /**
     * Which of Jira Cloud's three independent limits fired.
     *
     * <p>Recorded as a metric label rather than just logged: the points quota, the burst limit and
     * the per-issue limit behave differently and need different responses, so conflating them
     * makes tuning guesswork (docs/00-verified-capabilities.md 0.3).
     */
    public static Optional<String> rateLimitReason(Map<String, String> headers) {
        return Optional.ofNullable(headerIgnoringCase(headers, "RateLimit-Reason"));
    }

    private static String headerIgnoringCase(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
