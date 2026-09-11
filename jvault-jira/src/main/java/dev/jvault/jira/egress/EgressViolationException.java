package dev.jvault.jira.egress;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a Jira-bound payload would have carried content that must not reach Jira.
 *
 * <p>The operation fails closed: the Jira write does not happen, the caller gets an error built
 * from codes, and a high-severity audit event is written. There is no "log and continue" mode —
 * a leak that has been logged has still happened.
 */
public class EgressViolationException extends RuntimeException {

    private final List<EgressViolation> violations;

    public EgressViolationException(List<EgressViolation> violations) {
        super("Jira write refused by the egress guard: "
                + violations.stream().map(EgressViolation::toString).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    public List<EgressViolation> violations() {
        return violations;
    }
}
