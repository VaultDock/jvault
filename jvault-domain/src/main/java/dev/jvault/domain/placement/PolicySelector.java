package dev.jvault.domain.placement;

import java.util.Objects;

/**
 * Which contexts a policy applies to. A {@code null} component means "any".
 *
 * <p><strong>Why the weights are powers of two.</strong> Specificity is the sum of the weights
 * of the components a selector pins down. Because the weights are distinct powers of two, every
 * distinct <em>shape</em> of selector has a distinct specificity — subset sums of powers of two
 * are unique. And two selectors of the same shape can never both match one context, because
 * matching requires equality on every pinned component.
 *
 * <p>Together those two facts mean the highest-specificity match is always unique, so
 * {@link PlacementResolver} needs no tie-break rule and FR-CP-2's "ties are impossible" is a
 * property of the arithmetic rather than a validation someone has to remember to run.
 * {@link PolicySet} still rejects duplicate selectors, which is the other half of the guarantee.
 */
public record PolicySelector(
        String deploymentId,
        String projectKey,
        String issueTypeId,
        PartType partType,
        String fieldKey) {

    static final int WEIGHT_FIELD = 16;
    static final int WEIGHT_PART_TYPE = 8;
    static final int WEIGHT_ISSUE_TYPE = 4;
    static final int WEIGHT_PROJECT = 2;
    static final int WEIGHT_DEPLOYMENT = 1;

    public PolicySelector {
        deploymentId = blankToNull(deploymentId);
        projectKey = blankToNull(projectKey);
        issueTypeId = blankToNull(issueTypeId);
        fieldKey = blankToNull(fieldKey);
    }

    /** The selector that matches everything: the global default. */
    public static PolicySelector any() {
        return new PolicySelector(null, null, null, null, null);
    }

    public int specificity() {
        int s = 0;
        if (fieldKey != null) s += WEIGHT_FIELD;
        if (partType != null) s += WEIGHT_PART_TYPE;
        if (issueTypeId != null) s += WEIGHT_ISSUE_TYPE;
        if (projectKey != null) s += WEIGHT_PROJECT;
        if (deploymentId != null) s += WEIGHT_DEPLOYMENT;
        return s;
    }

    public boolean matches(PlacementContext context) {
        Objects.requireNonNull(context, "context");
        return pinnedMatches(deploymentId, context.deploymentId())
                && pinnedMatches(projectKey, context.projectKey())
                && pinnedMatches(issueTypeId, context.issueTypeId())
                && (partType == null || partType == context.partType())
                && pinnedMatches(fieldKey, context.fieldKey());
    }

    /** A pinned selector component matches only an exactly equal, present context value. */
    private static boolean pinnedMatches(String selectorValue, String contextValue) {
        return selectorValue == null || selectorValue.equals(contextValue);
    }

    /** Human-readable form for validation messages and audit records. */
    public String describe() {
        var sb = new StringBuilder();
        append(sb, "deployment", deploymentId);
        append(sb, "project", projectKey);
        append(sb, "issueType", issueTypeId);
        append(sb, "partType", partType == null ? null : partType.name());
        append(sb, "field", fieldKey);
        return sb.isEmpty() ? "(global default)" : sb.toString();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value == null) return;
        if (!sb.isEmpty()) sb.append(", ");
        sb.append(name).append('=').append(value);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
