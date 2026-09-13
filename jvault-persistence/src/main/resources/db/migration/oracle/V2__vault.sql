-- jvault content, tickets, comments and grants. Oracle.
--
-- UNVERIFIED: not exercised against a real Oracle in this repository (decision D5).
--
-- The portability choices from V1 carry over: CHAR(36) identifiers rather than uuid, plain
-- TIMESTAMP holding UTC rather than a zoned type, and no JSON columns. The reasons are in
-- docs/decisions.md (D5) and in postgresql/V1__outbox.sql.
--
-- The one rule specific to this file: no column in it ever holds ticket content. Content lives
-- encrypted in a storage backend; what is here is the metadata needed to find it, prove it has
-- not changed, and decide who may read it. The single exception is deliberate and marked.

CREATE TABLE ticket_record (
    ticket_ref      CHAR(36)      NOT NULL,
    deployment_id   VARCHAR2(64)   NOT NULL,
    project_key     VARCHAR2(64)   NOT NULL,
    issue_type_id   VARCHAR2(64)   NOT NULL,
    dedupe_key      VARCHAR2(200),
    correlation_id  VARCHAR2(200),
    -- Who really caused this ticket, kept apart from the identity that acts in Jira. For
    -- Kafka-originated work the Jira creator is the integration identity, which is accurate —
    -- the event created the ticket, not a person — and this is where the person survives
    -- (docs/07-kafka.md 7.6).
    origin_channel    VARCHAR2(16)   NOT NULL,
    origin_actor_id   VARCHAR2(200),
    origin_actor_name VARCHAR2(200),
    acting_identity   VARCHAR2(200),
    state           VARCHAR2(24)   NOT NULL,
    jira_issue_id   VARCHAR2(64),
    jira_issue_key  VARCHAR2(64),
    created_at      TIMESTAMP     NOT NULL,
    CONSTRAINT pk_ticket_record PRIMARY KEY (ticket_ref)
);

-- The only thing standing between a replayed Kafka message and a second Jira issue. It is a
-- constraint rather than a check-then-insert because two nodes can pass the same check.
-- Oracle has no partial index, but it does not index rows whose every key column is NULL, so a
-- function-based index that nulls the whole key when there is no dedupe key behaves the same way
-- as the filtered index the other engines use.
CREATE UNIQUE INDEX uq_ticket_dedupe ON ticket_record (
    CASE WHEN dedupe_key IS NULL THEN NULL ELSE deployment_id END,
    CASE WHEN dedupe_key IS NULL THEN NULL ELSE project_key END,
    dedupe_key);

-- Supports the ambiguity reconciler, which searches by correlation id for an issue that may or
-- may not have been created.
CREATE INDEX ix_ticket_correlation ON ticket_record (correlation_id);

-- The values bound for Jira. This is the deliberate exception to the no-content rule: for a
-- field placed in Jira, the value here is the text that Jira will hold anyway, so keeping it is
-- no wider an exposure than the issue itself. For a field placed externally it is the surrogate,
-- never the content the surrogate stands for.
CREATE TABLE ticket_field (
    ticket_ref  CHAR(36)      NOT NULL,
    field_key   VARCHAR2(128)  NOT NULL,
    field_value VARCHAR2(4000),
    CONSTRAINT pk_ticket_field PRIMARY KEY (ticket_ref, field_key),
    CONSTRAINT fk_ticket_field_ticket FOREIGN KEY (ticket_ref)
        REFERENCES ticket_record (ticket_ref)
);

-- Which content parts this ticket's Jira payload refers to, in order.
CREATE TABLE ticket_external_part (
    ticket_ref  CHAR(36)  NOT NULL,
    ordinal     NUMBER(10)  NOT NULL,
    content_ref CHAR(36)  NOT NULL,
    CONSTRAINT pk_ticket_external_part PRIMARY KEY (ticket_ref, ordinal),
    CONSTRAINT fk_ticket_external_part_ticket FOREIGN KEY (ticket_ref)
        REFERENCES ticket_record (ticket_ref)
);

-- One addressable piece of content. The identity a link points at: versions, storage objects and
-- backends change beneath it, this row does not (FR-LNK-1).
CREATE TABLE content_part (
    content_ref        CHAR(36)       NOT NULL,
    ticket_ref         CHAR(36)       NOT NULL,
    part_type          VARCHAR2(24)    NOT NULL,
    field_key          VARCHAR2(128),
    classification     VARCHAR2(24)    NOT NULL,
    jira_surrogate     VARCHAR2(4000),
    current_version_no NUMBER(10)       NOT NULL,
    created_at         TIMESTAMP      NOT NULL,
    CONSTRAINT pk_content_part PRIMARY KEY (content_ref),
    CONSTRAINT fk_content_part_ticket FOREIGN KEY (ticket_ref)
        REFERENCES ticket_record (ticket_ref)
);

CREATE INDEX ix_content_part_ticket ON content_part (ticket_ref, part_type);

-- One stored version of one part. Immutable once written: a correction is a new version, which
-- is what makes a retention hold and an audit trail mean anything.
CREATE TABLE content_version (
    version_id         CHAR(36)      NOT NULL,
    content_ref        CHAR(36)      NOT NULL,
    version_no         NUMBER(10)      NOT NULL,
    key_ring           VARCHAR2(64)   NOT NULL,
    -- The KEK identifier, never the key. Which KEK is authoritative after a rotation is decided
    -- by this column rather than by the header in the object, so rewrapping does not require
    -- rewriting terabytes of ciphertext (docs/09-encryption.md).
    kek_id             VARCHAR2(128)  NOT NULL,
    wrapped_dek        BLOB          NOT NULL,
    plaintext_sha256   BLOB          NOT NULL,
    ciphertext_sha256  BLOB          NOT NULL,
    size_bytes         NUMBER(19)       NOT NULL,
    media_type         VARCHAR2(128),
    -- The original filename, encrypted. '2026-Q3-layoffs-final.xlsx' is frequently the most
    -- sensitive thing about a file, and a filename column in the clear would undo much of what
    -- encrypting the bytes achieved (docs/04-data-model.md 4.2).
    display_name_enc   BLOB ,
    display_name_kek   VARCHAR2(128),
    display_name_dek   BLOB ,
    display_name_label VARCHAR2(64),
    storage_route      VARCHAR2(64)   NOT NULL,
    object_key         VARCHAR2(1024) NOT NULL,
    backend_version_id VARCHAR2(256),
    created_at         TIMESTAMP     NOT NULL,
    CONSTRAINT pk_content_version PRIMARY KEY (version_id),
    CONSTRAINT uq_content_version UNIQUE (content_ref, version_no),
    CONSTRAINT fk_content_version_part FOREIGN KEY (content_ref)
        REFERENCES content_part (content_ref)
);

CREATE TABLE ticket_comment (
    comment_ref     CHAR(36)      NOT NULL,
    ticket_ref      CHAR(36)      NOT NULL,
    -- What Jira shows: either the comment itself, or the surrogate standing in for it.
    jira_body       VARCHAR2(4000) NOT NULL,
    content_ref     CHAR(36),
    placement       VARCHAR2(16)   NOT NULL,
    author_id       VARCHAR2(128),
    jira_comment_id VARCHAR2(64),
    created_at      TIMESTAMP     NOT NULL,
    CONSTRAINT pk_ticket_comment PRIMARY KEY (comment_ref),
    CONSTRAINT fk_ticket_comment_ticket FOREIGN KEY (ticket_ref)
        REFERENCES ticket_record (ticket_ref)
);

CREATE INDEX ix_ticket_comment_ticket ON ticket_comment (ticket_ref, created_at);

-- Grants held by jvault. Never the whole answer: a read is allowed only where a grant here and a
-- live Jira permission check agree (docs/10-authorization.md, the INTERSECT rule).
CREATE TABLE acl_grant (
    grant_id          CHAR(36)      NOT NULL,
    scope_type        VARCHAR2(16)   NOT NULL,
    scope_id          VARCHAR2(128)  NOT NULL,
    scope_parent_type VARCHAR2(16),
    scope_parent_id   VARCHAR2(128),
    space_id          VARCHAR2(128)  NOT NULL,
    principal_kind    VARCHAR2(16)   NOT NULL,
    principal_id      VARCHAR2(200)  NOT NULL,
    can_delegate      NUMBER(1)     NOT NULL,
    delegation_depth  NUMBER(10)      NOT NULL,
    granted_by_grant  CHAR(36),
    granted_by_kind   VARCHAR2(16),
    granted_by_id     VARCHAR2(200),
    expires_at        TIMESTAMP,
    created_at        TIMESTAMP     NOT NULL,
    CONSTRAINT pk_acl_grant PRIMARY KEY (grant_id)
);

-- The lookup every authorization decision makes: the grants at one scope.
CREATE INDEX ix_acl_grant_scope ON acl_grant (scope_type, scope_id);

-- Supports cascading revocation, which must find everything granted under a grant.
CREATE INDEX ix_acl_grant_parent ON acl_grant (granted_by_grant);

CREATE TABLE acl_grant_permission (
    grant_id   CHAR(36)    NOT NULL,
    permission VARCHAR2(32) NOT NULL,
    CONSTRAINT pk_acl_grant_permission PRIMARY KEY (grant_id, permission),
    CONSTRAINT fk_acl_grant_permission FOREIGN KEY (grant_id)
        REFERENCES acl_grant (grant_id)
);

-- A scope that does not inherit from its parent. Rare, and load-bearing when present: it is the
-- difference between a ticket the space can read and one only its grantees can.
CREATE TABLE acl_inheritance_break (
    scope_type VARCHAR2(16)  NOT NULL,
    scope_id   VARCHAR2(128) NOT NULL,
    CONSTRAINT pk_acl_inheritance_break PRIMARY KEY (scope_type, scope_id)
);
