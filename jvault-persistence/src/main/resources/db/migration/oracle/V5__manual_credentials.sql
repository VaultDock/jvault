-- The identity a manually imported credential authenticates as. Oracle.
--
-- UNVERIFIED: not exercised against a real Oracle in this repository (decision D5).
--
-- Jira Cloud API tokens are used with basic auth — email and token together (§0.10) — so the
-- address is half the credential rather than a display detail. It is stored in the clear because
-- it is an identifier, not a secret: the token beside it is what is encrypted, and an email that
-- cannot be read would make the connection impossible to attribute or to rotate.
ALTER TABLE jira_connection ADD auth_email VARCHAR2(320);

-- What kind of credential this is, so the checker knows whether to send Bearer or Basic and
-- whether a refresh is even possible. Derivable from flow_used today, and derivable things that
-- matter for security have a habit of being derived wrongly.
ALTER TABLE jira_connection ADD credential_kind VARCHAR2(24) NOT NULL DEFAULT 'OAUTH';
