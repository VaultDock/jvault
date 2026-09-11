package dev.jvault.jira.egress;

/** The Jira mutations jvault performs. Mirrors {@code jira_outbox.operation}. */
public enum JiraOperation {

    CREATE_ISSUE(false),
    UPDATE_FIELDS(true),
    ADD_COMMENT(false),
    EDIT_COMMENT(true),
    DELETE_COMMENT(true),
    ADD_ATTACHMENT(false),
    DELETE_ATTACHMENT(true),
    UPSERT_REMOTE_LINK(true),
    DELETE_REMOTE_LINK(true),
    SET_PROPERTY(true);

    private final boolean idempotent;

    JiraOperation(boolean idempotent) {
        this.idempotent = idempotent;
    }

    /**
     * Whether repeating this operation with the same input leaves Jira in the same state.
     *
     * <p>This decides what an <em>unknown</em> outcome means. When a request times out after
     * being sent, jvault cannot tell whether Jira applied it — but for an idempotent operation it
     * does not need to: repeating a remote-link upsert (deduplicated by {@code globalId}) or a
     * property {@code PUT} reaches the same end state either way, so the honest classification is
     * "retry" rather than "stop and reconcile".
     *
     * <p>Only the non-idempotent operations need the ambiguity protocol, and creating an issue is
     * the one that really matters — Jira offers no idempotency key for it
     * (docs/12-reliability.md 12.4.2). Marking the others idempotent is what keeps that protocol
     * rare rather than routine.
     *
     * <p>{@code DELETE} operations count as idempotent: deleting something already gone is a
     * no-op, which is the same end state.
     */
    public boolean isIdempotent() {
        return idempotent;
    }
}
