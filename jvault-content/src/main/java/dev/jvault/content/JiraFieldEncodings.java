package dev.jvault.content;

import dev.jvault.jira.egress.JiraFieldEncoding;

/**
 * What shape a field's value takes on the wire.
 *
 * <p>Jira is the authority on this and says so in create metadata: a priority is an object with
 * an id, labels are an array of strings, a checkbox group is an array of objects with ids. The
 * field key alone cannot tell them apart — {@code customfield_10021} could be any of them —
 * which is why deriving the encoding from the key was enough for system fields and wrong for
 * every custom one.
 *
 * <p>It is asked at dispatch, from the assembler, for the same reason the assembler re-reads
 * everything else there: the outbox row holds identifiers, and what is current at the moment of
 * the write is a better answer than what was true when the ticket was made.
 */
public interface JiraFieldEncodings {

    /**
     * @return the encoding for this field, never {@code null}. An implementation that cannot
     *         find the field says so by falling back to what the key implies rather than by
     *         returning nothing, because a missing encoding has no useful meaning downstream
     */
    JiraFieldEncoding encodingFor(String projectKey, String issueTypeId, String fieldKey);

    /**
     * The table alone, with no metadata behind it.
     *
     * <p>Correct for every system field and for nothing else. It is what the assembler does when
     * no deployment is configured to ask — a test, or an offline reconstruction of a payload.
     */
    static JiraFieldEncodings byFieldKey() {
        return (projectKey, issueTypeId, fieldKey) ->
                JiraFieldEncoding.forField(null, null, null, fieldKey);
    }
}
