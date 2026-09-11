package dev.jvault.jira.egress;

/** The Jira mutations jvault performs. Mirrors {@code jira_outbox.operation}. */
public enum JiraOperation {
    CREATE_ISSUE,
    UPDATE_FIELDS,
    ADD_COMMENT,
    EDIT_COMMENT,
    DELETE_COMMENT,
    ADD_ATTACHMENT,
    DELETE_ATTACHMENT,
    UPSERT_REMOTE_LINK,
    DELETE_REMOTE_LINK,
    SET_PROPERTY
}
