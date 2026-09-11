-- jvault outbox, Microsoft SQL Server. See postgresql/V1__outbox.sql for the shared rationale.
--
-- UNVERIFIED: not exercised against a real SQL Server in this repository (decision D5).

CREATE TABLE jira_outbox (
    id                 CHAR(36)        NOT NULL,
    ticket_ref         NVARCHAR(64)    NOT NULL,
    deployment_id      NVARCHAR(64),
    issue_lane         NVARCHAR(128)   NOT NULL,
    operation          NVARCHAR(32)    NOT NULL,
    effect_key         NVARCHAR(200)   NOT NULL,
    payload_ref        NVARCHAR(4000)  NOT NULL,
    identity_ref       NVARCHAR(128)   NOT NULL,
    state              NVARCHAR(16)    NOT NULL,
    attempts           INT             NOT NULL,
    next_attempt_at    DATETIME2       NOT NULL,
    last_error_code    NVARCHAR(256),
    attempt_started_at DATETIME2,
    created_at         DATETIME2       NOT NULL,
    CONSTRAINT pk_jira_outbox PRIMARY KEY NONCLUSTERED (id),
    CONSTRAINT uq_jira_outbox_effect UNIQUE (ticket_ref, effect_key)
);

-- Clustered on the claim order rather than on the primary key. The dequeue reads due rows oldest
-- first under UPDLOCK/READPAST; clustering on a random CHAR(36) id would scatter those reads
-- across the table and make lock escalation — which READPAST cannot save us from — far likelier.
CREATE CLUSTERED INDEX ix_jira_outbox_due ON jira_outbox (state, next_attempt_at, created_at);

CREATE INDEX ix_jira_outbox_lane ON jira_outbox (issue_lane, state);
