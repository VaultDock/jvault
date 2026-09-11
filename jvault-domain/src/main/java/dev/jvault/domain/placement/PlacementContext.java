package dev.jvault.domain.placement;

import java.util.Objects;

/**
 * The thing a policy is resolved <em>for</em>: one part of one ticket in one place.
 *
 * @param deploymentId the configured Jira deployment
 * @param projectKey   Jira project key, e.g. {@code SEC}
 * @param issueTypeId  Jira issue type id, e.g. {@code 10004}
 * @param partType     which part of the ticket
 * @param fieldKey     Jira field key ({@code description}, {@code customfield_10010}), or
 *                     {@code null} for parts that are not fields — comments and attachments
 */
public record PlacementContext(
        String deploymentId,
        String projectKey,
        String issueTypeId,
        PartType partType,
        String fieldKey) {

    public PlacementContext {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(issueTypeId, "issueTypeId");
        Objects.requireNonNull(partType, "partType");
        fieldKey = blankToNull(fieldKey);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
