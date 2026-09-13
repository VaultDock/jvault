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
public interface JiraMetadataGateway extends UserDirectory {

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

    /** Who jvault is to Jira. */
    CurrentUser currentUser();

    /**
     * People matching a query.
     *
     * @param assignable when true, only those who can be assigned work in this project. The
     *                   narrower list is the right default for an assignee and the wrong one for
     *                   a reporter, so the caller chooses rather than this guessing
     */
    List<UserRef> searchUsers(String projectKey, String query, boolean assignable);

    /**
     * Issues that could be the parent of something of this type.
     *
     * <p>A parent sits exactly one level up: an epic parents a task, a task parents a subtask,
     * and nothing parents an epic. Offering anything else produces a create that Jira refuses
     * with a field error, which is a poor way to find out — the earlier version offered every
     * non-subtask and every ticket given a task as its parent failed.
     *
     * @param childIssueTypeId the type of the issue being created, whose level decides the
     *                         answer. An empty list means this type takes no parent
     */
    List<IssueRef> searchIssues(String projectKey, String query, String childIssueTypeId);

    /**
     * The account jvault is acting as.
     *
     * <p>Its locale matters beyond politeness: Jira returns field names in the language of the
     * <em>authenticated</em> account and ignores Accept-Language on the createmeta endpoints, so
     * this is the language the field labels arrive in. With a service account (decision D4) that
     * is one language for everybody, which is a thing the interface has to be honest about
     * rather than quietly pretend otherwise.
     *
     * @param locale a Java-style locale such as {@code en_GB}, or {@code null} if Jira omits it
     */
    record CurrentUser(String accountId, String displayName, String locale) {
    }

    /**
     * Someone a user field can be set to.
     *
     * <p>An account id is the only thing Jira accepts and the last thing a person knows, which is
     * why a text box asking for one is not a control so much as an obstacle.
     */
    record UserRef(String accountId, String displayName, String email, boolean active) {
    }

    /**
     * An issue a parent field can point at.
     *
     * <p>Jira wants a key. A key is at least something a person recognises, unlike an account id,
     * but nobody remembers which of forty keys is the epic they meant.
     */
    record IssueRef(String key, String summary, String issueTypeName) {
    }

    record Project(String id, String key, String name, String style) {
    }

    /**
     * @param hierarchyLevel where this type sits: 1 for an epic, 0 for ordinary work, -1 for a
     *                       subtask. It is what decides which issues may be a parent, and Jira
     *                       refuses the combination rather than ignoring it
     */
    record IssueType(String id, String name, boolean subtask, String description,
                     int hierarchyLevel) {
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
