-- jvault application sessions and per-user Jira connections. Oracle.
--
-- UNVERIFIED: not exercised against a real Oracle in this repository (decision D5).
--
-- Two tables because they have different lifetimes and different failure modes. A session ends
-- when somebody signs out or it expires; a Jira connection outlives it, because the refresh
-- token is what lets jvault check that person's Jira permissions without sending them back
-- through consent every morning.

CREATE TABLE auth_state (
    state_id     CHAR(36)      NOT NULL,
    redirect_to  VARCHAR2(512),
    created_at   TIMESTAMP     NOT NULL,
    expires_at   TIMESTAMP     NOT NULL,
    CONSTRAINT pk_auth_state PRIMARY KEY (state_id)
);

-- The OAuth state parameter is the only thing standing between this flow and an attacker
-- getting their own authorization code redeemed into somebody else's session. Single-use, so
-- the row is deleted on redemption rather than marked.
CREATE INDEX ix_auth_state_expiry ON auth_state (expires_at);

CREATE TABLE user_session (
    session_id   CHAR(36)      NOT NULL,
    account_id   VARCHAR2(128)  NOT NULL,
    display_name VARCHAR2(200),
    email        VARCHAR2(320),
    locale       VARCHAR2(32),
    created_at   TIMESTAMP     NOT NULL,
    expires_at   TIMESTAMP     NOT NULL,
    CONSTRAINT pk_user_session PRIMARY KEY (session_id)
);

CREATE INDEX ix_user_session_account ON user_session (account_id);
CREATE INDEX ix_user_session_expiry ON user_session (expires_at);

-- One person's authorization against one Jira deployment.
--
-- The tokens are credentials for somebody's Jira account, which makes this the most sensitive
-- table in the schema after the content keys: a readable access token is that person's Jira
-- access, and a readable refresh token is that access renewed indefinitely. They are encrypted
-- with the same envelope the content uses, bound to the row that holds them.
CREATE TABLE jira_connection (
    account_id        VARCHAR2(128)  NOT NULL,
    deployment_id     VARCHAR2(64)   NOT NULL,
    cloud_id          VARCHAR2(128),
    site_url          VARCHAR2(512),
    key_ring          VARCHAR2(64)   NOT NULL,
    kek_id            VARCHAR2(128)  NOT NULL,
    wrapped_dek       BLOB          NOT NULL,
    access_token_enc  BLOB          NOT NULL,
    refresh_token_enc BLOB ,
    access_expires_at TIMESTAMP     NOT NULL,
    scopes            VARCHAR2(1024),
    -- Recorded rather than assumed: Cloud 3LO does not expose PKCE and Data Center does, so
    -- which protections this connection actually had is a reviewable fact (docs/10 §10.3).
    flow_used         VARCHAR2(64)   NOT NULL,
    connected_at      TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL,
    CONSTRAINT pk_jira_connection PRIMARY KEY (account_id, deployment_id)
);
