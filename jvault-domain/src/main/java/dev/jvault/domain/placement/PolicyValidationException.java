package dev.jvault.domain.placement;

import java.util.List;

/**
 * A policy set was rejected. Carries every problem found, because administrators editing
 * configuration should see all of them at once rather than one per save.
 *
 * <p>Messages reference selectors and field keys only — never content.
 */
public class PolicyValidationException extends RuntimeException {

    private final List<String> problems;

    public PolicyValidationException(List<String> problems) {
        super("Placement policy set is invalid: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
