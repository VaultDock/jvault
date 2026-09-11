package dev.jvault.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * RFC 9457 problem documents, built from codes rather than from values.
 *
 * <p>The rule that governs everything here: <strong>an error response never echoes what the
 * caller submitted and never contains stored content</strong>. A validation failure names the
 * field and the violation; it does not quote the value that failed, because the value is often
 * exactly what must not be written down — and an error body travels into logs, browser consoles,
 * screenshots and support tickets (docs/06-rest-api.md 6.1, FR-API-4).
 */
public final class ApiProblem {

    private static final String BASE = "https://jvault.example/probs/";

    private ApiProblem() {
    }

    public static ProblemDetail of(HttpStatus status, String type, String title) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(BASE + type));
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail fieldValidation(List<FieldError> errors) {
        ProblemDetail problem = of(HttpStatus.BAD_REQUEST, "field-validation",
                "The request failed validation");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * The caller has no usable Jira authorization.
     *
     * <p>Deliberately not a 403. Under the default INTERSECT mode a missing Jira connection is a
     * prerequisite that has not been met, not a permission that has been refused — so the response
     * carries a connect link and the UI can offer a way forward instead of a dead end.
     */
    public static ProblemDetail jiraNotConnected(String connectUrl) {
        ProblemDetail problem = of(HttpStatus.CONFLICT, "jira-not-connected",
                "Jira is not connected");
        problem.setProperty("connectUrl", connectUrl);
        return problem;
    }

    /**
     * A dependency needed to decide authorization was unreachable.
     *
     * <p>503 rather than 403: a user locked out by a Jira outage should be told the check cannot
     * be made, not that they have lost access.
     */
    public static ProblemDetail authorizationUnavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "authorization-dependency-unavailable",
                "Access cannot be checked right now");
    }

    public static ProblemDetail forbidden(String reasonCode) {
        ProblemDetail problem = of(HttpStatus.FORBIDDEN, "forbidden", "Access denied");
        // A stable code, so a support conversation can proceed without anyone quoting content.
        problem.setProperty("reason", reasonCode);
        return problem;
    }

    public static ProblemDetail notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found");
    }

    public static ProblemDetail gone(String detail) {
        ProblemDetail problem = of(HttpStatus.GONE, "content-purged", "Content has been purged");
        problem.setDetail(detail);
        return problem;
    }

    public static ProblemDetail idempotencyKeyReuse() {
        return of(HttpStatus.CONFLICT, "idempotency-key-reuse",
                "This Idempotency-Key was used with a different request");
    }

    public static ProblemDetail requestInProgress() {
        return of(HttpStatus.CONFLICT, "request-in-progress",
                "A request with this Idempotency-Key is still being processed");
    }

    /** @param code a stable, enumerable violation code — never the offending value */
    public record FieldError(String field, String code, Map<String, Object> constraints) {

        public static FieldError of(String field, String code) {
            return new FieldError(field, code, Map.of());
        }
    }
}
