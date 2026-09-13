package dev.jvault.jira.gateway;

import java.util.List;

/**
 * Read access to the configuration that drives a create form.
 *
 * <p>jvault renders whatever Jira exposes here and is honest about the rest. What this cannot
 * reach — field Behaviours injected by apps, workflow validators, screen tab layout — is the
 * boundary of Tier A in the parity scope (docs/02-jira-parity-scope.md), and pretending otherwise
 * would produce a form that looks right and rejects on submit.
 */
public interface JiraMetadataGateway {

    /** Projects the acting identity may create issues in. */
    List<Project> projects();

    List<IssueType> issueTypes(String projectKey);

    /**
     * Field metadata for one project and issue type.
     *
     * <p>From the replacement createmeta endpoints, never the deprecated aggregate form
     * (docs/00-verified-capabilities.md 0.5).
     */
    List<FieldMeta> fields(String projectKey, String issueTypeId);

    record Project(String id, String key, String name, String style) {
    }

    record IssueType(String id, String name, boolean subtask, String description) {
    }

    /**
     * One field as Jira describes it.
     *
     * @param schemaType   Jira's own type — {@code string}, {@code array}, {@code user}…
     * @param customType   the custom field type's short name, or {@code null} for a system field
     * @param allowedValues the permitted values, when Jira enumerates them. Large option sets are
     *                      truncated and flagged, because a select with ten thousand options is a
     *                      lookup, not a dropdown
     * @param operations    what Jira says may be done to the field, or {@code null} if it did not
     *                      say. An empty list and an absent one mean opposite things, so they are
     *                      not collapsed into each other
     */
    record FieldMeta(String key,
                     String name,
                     boolean required,
                     String schemaType,
                     String customType,
                     List<AllowedValue> allowedValues,
                     boolean hasMoreOptions,
                     List<String> operations) {

        public FieldMeta {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            operations = operations == null ? null : List.copyOf(operations);
        }

        /**
         * Whether the field can be set at all, as opposed to merely being shown.
         *
         * <p>An empty list is Jira saying no — the development summary field is the common case.
         * Silence is not: a deployment that omits the key would otherwise render every field on
         * the form inert, so absence is read as permissive and Jira validates on submit.
         */
        public boolean settable() {
            return operations == null || operations.contains("set");
        }
    }

    record AllowedValue(String id, String value) {
    }
}
