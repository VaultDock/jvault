-- jvault outbox, PostgreSQL.
--
-- One logical schema, three physical scripts (decision D5). Portability choices that look odd
-- in isolation are explained in docs/decisions.md:
--   * CHAR(36) identifiers rather than uuid — Oracle has no UUID type and SQL Server's
--     uniqueidentifier sorts differently, which would change index behaviour between deployments.
--   * plain TIMESTAMP holding UTC rather than timestamptz — SQL Server's datetime2 carries no
--     zone, and depending on three vendors' server time zones agreeing is a bug awaiting a clock
--     change.
--   * payload_ref is text, not jsonb. It holds identifiers only, never content.

CREATE TABLE jira_outbox (
    id                 CHAR(36)      NOT NULL,
    ticket_ref         VARCHAR(64)   NOT NULL,
    deployment_id      VARCHAR(64),
    issue_lane         VARCHAR(128)  NOT NULL,
    operation          VARCHAR(32)   NOT NULL,
    effect_key         VARCHAR(200)  NOT NULL,
    payload_ref        VARCHAR(4000) NOT NULL,
    identity_ref       VARCHAR(128)  NOT NULL,
    state              VARCHAR(16)   NOT NULL,
    attempts           INTEGER       NOT NULL,
    next_attempt_at    TIMESTAMP     NOT NULL,
    last_error_code    VARCHAR(256),
    attempt_started_at TIMESTAMP,
    created_at         TIMESTAMP     NOT NULL,
    CONSTRAINT pk_jira_outbox PRIMARY KEY (id),
    -- The constraint that makes enqueueing idempotent, and the only thing standing between a
    -- replayed message and a duplicated Jira effect.
    CONSTRAINT uq_jira_outbox_effect UNIQUE (ticket_ref, effect_key)
);

-- Supports the claim query: due entries, oldest first.
CREATE INDEX ix_jira_outbox_due ON jira_outbox (state, next_attempt_at, created_at);

-- Supports lane grouping and the per-issue serialisation the dispatcher depends on.
CREATE INDEX ix_jira_outbox_lane ON jira_outbox (issue_lane, state);
