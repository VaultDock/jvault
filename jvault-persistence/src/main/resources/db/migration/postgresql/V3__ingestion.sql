-- jvault Kafka ingestion state. PostgreSQL.
--
-- Two jobs, and only the second one needs explaining.
--
-- The offset key records what has already been handled, so a redelivery after a rebalance is
-- recognised rather than reprocessed. Kafka's own offsets cannot do this: a commit and the work
-- it covers are not one transaction, and a consumer that dies between them redelivers work that
-- already happened.
--
-- The dedupe key is the business-level one. The same event republished under a new offset — a
-- producer retry, a topic replay, a migration — is the same event, and only a constraint on
-- something the message itself carries can say so.

CREATE TABLE ingested_message (
    topic           VARCHAR(255)  NOT NULL,
    partition_no    INTEGER       NOT NULL,
    message_offset  BIGINT        NOT NULL,
    mapping_id      VARCHAR(128)  NOT NULL,
    dedupe_key      VARCHAR(200),
    correlation_id  VARCHAR(200)  NOT NULL,
    state           VARCHAR(24)   NOT NULL,
    ticket_ref      CHAR(36),
    attempts        INTEGER       NOT NULL,
    last_error_code VARCHAR(256),
    quarantine_ref  CHAR(36),
    received_at     TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT pk_ingested_message PRIMARY KEY (topic, partition_no, message_offset)
);

-- The constraint that makes a replay a no-op rather than a second ticket. Partial, because a
-- message that never reached a dedupe key has no claim to uniqueness and must not become a
-- magnet that rejects every later keyless message.
CREATE UNIQUE INDEX uq_ingested_dedupe
    ON ingested_message (mapping_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;

-- Supports the reconciliation sweep, which looks for work that stalled part-way.
CREATE INDEX ix_ingested_state ON ingested_message (state, updated_at);

-- The payload of a message that could not be processed, kept so that someone can see what
-- arrived — encrypted, because a payload that failed validation is still whatever the producer
-- put in it, and that is frequently the most sensitive thing in the system.
CREATE TABLE quarantined_payload (
    quarantine_ref CHAR(36)      NOT NULL,
    correlation_id VARCHAR(200)  NOT NULL,
    key_ring       VARCHAR(64)   NOT NULL,
    kek_id         VARCHAR(128)  NOT NULL,
    wrapped_dek    BYTEA         NOT NULL,
    payload_enc    BYTEA         NOT NULL,
    size_bytes     INTEGER       NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_quarantined_payload PRIMARY KEY (quarantine_ref)
);

CREATE INDEX ix_quarantined_correlation ON quarantined_payload (correlation_id);
