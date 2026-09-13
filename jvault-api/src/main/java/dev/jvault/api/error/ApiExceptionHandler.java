package dev.jvault.api.error;

import dev.jvault.authz.GrantService;
import dev.jvault.content.ContentService;
import dev.jvault.domain.placement.PolicyValidationException;
import dev.jvault.jira.egress.EgressViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into RFC 9457 problem documents.
 *
 * <p>One rule governs all of it: <strong>nothing here echoes what the caller sent or what the
 * vault holds</strong>. An exception message is written for a developer reading a log, and this is
 * the one place where such a message would be handed to a client, put in a browser console, and
 * pasted into a support ticket. So the mapping is from exception <em>type</em> to a fixed
 * description, and only explicitly safe values — field names, stable codes — travel with it.
 *
 * <p>The catch-all deliberately says nothing at all. An unhandled exception is by definition one
 * whose message nobody has vetted.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * The guard refused a Jira write.
     *
     * <p>Reported as a server error rather than a client one: the caller did nothing wrong, jvault
     * was about to leak and stopped itself. Only the violation codes travel — never the content
     * that triggered them, which is the whole point of the guard.
     */
    @ExceptionHandler(EgressViolationException.class)
    public ProblemDetail onEgressViolation(EgressViolationException e) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR,
                "content-egress-refused", "The request was refused to prevent a content leak");
        problem.setProperty("violations", e.violations().stream()
                .map(v -> java.util.Map.of("field", v.fieldKey(), "code", v.code()))
                .toList());
        return problem;
    }

    @ExceptionHandler(GrantService.GrantRefusedException.class)
    public ProblemDetail onGrantRefused(GrantService.GrantRefusedException e) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.UNPROCESSABLE_ENTITY,
                "grant-refused", "The grant was refused");
        // A stable code, not the message: the message names principals and scopes, which is fine
        // for a log and unnecessary for a client.
        problem.setProperty("reason", e.code());
        return problem;
    }

    @ExceptionHandler(PolicyValidationException.class)
    public ProblemDetail onInvalidPolicy(PolicyValidationException e) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.UNPROCESSABLE_ENTITY,
                "policy-invalid", "The placement policy set is invalid");
        // Policy problems name selectors and field keys, which an administrator needs in order to
        // fix them, and contain no ticket content by construction.
        problem.setProperty("problems", e.problems());
        return problem;
    }

    @ExceptionHandler(ContentService.ContentException.class)
    public ProblemDetail onContentFailure(ContentService.ContentException e) {
        return ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR,
                "content-unavailable", "The content could not be read or written");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        // Jackson's message quotes the offending input. That input is the request body.
        return ApiProblem.of(HttpStatus.BAD_REQUEST,
                "malformed-request", "The request body could not be parsed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return ApiProblem.of(HttpStatus.BAD_REQUEST,
                "invalid-request", "The request could not be accepted");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onAnythingElse(Exception e) {
        // Nothing from the exception travels. An unhandled exception is by definition one whose
        // message nobody has vetted for content, and the trace id is how it gets correlated.
        return ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error", "The request could not be completed");
    }
}
