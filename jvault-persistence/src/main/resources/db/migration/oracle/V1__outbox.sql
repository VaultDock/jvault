-- jvault outbox, Oracle. See postgresql/V1__outbox.sql for the shared rationale.
--
-- UNVERIFIED: not exercised against a real Oracle in this repository (decision D5).
--
-- NUMBER(1) rather than BOOLEAN is not needed here, but note for later tables: Oracle had no SQL
-- BOOLEAN before 23c, so shared columns use SMALLINT 0/1.

CREATE TABLE jira_outbox (
    id                 CHAR(36)        NOT NULL,
    ticket_ref         VARCHAR2(64)    NOT NULL,
    deployment_id      VARCHAR2(64),
    issue_lane         VARCHAR2(128)   NOT NULL,
    operation          VARCHAR2(32)    NOT NULL,
    effect_key         VARCHAR2(200)   NOT NULL,
    payload_ref        VARCHAR2(4000)  NOT NULL,
    identity_ref       VARCHAR2(128)   NOT NULL,
    state              VARCHAR2(16)    NOT NULL,
    attempts           NUMBER(10)      NOT NULL,
    next_attempt_at    TIMESTAMP       NOT NULL,
    last_error_code    VARCHAR2(256),
    attempt_started_at TIMESTAMP,
    created_at         TIMESTAMP       NOT NULL,
    CONSTRAINT pk_jira_outbox PRIMARY KEY (id),
    CONSTRAINT uq_jira_outbox_effect UNIQUE (ticket_ref, effect_key)
);

CREATE INDEX ix_jira_outbox_due ON jira_outbox (state, next_attempt_at, created_at);

CREATE INDEX ix_jira_outbox_lane ON jira_outbox (issue_lane, state);
