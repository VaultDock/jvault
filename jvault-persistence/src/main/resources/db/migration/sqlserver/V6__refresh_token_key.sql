-- The refresh token's own wrapped key. Microsoft SQL Server.
--
-- UNVERIFIED: not exercised against a real SQL Server in this repository (decision D5).
--
-- Two secrets sealed by two calls are sealed under two data keys, and only one of them was
-- stored: the refresh token's key was discarded at the moment it was created, so its ciphertext
-- could never be opened again. It went unnoticed because the only connections that existed were
-- manually imported ones, which have no refresh token.
--
-- Two columns for two keys rather than one envelope over both, so that "refresh_token_enc IS
-- NULL means there is no refresh token" stays true.
ALTER TABLE jira_connection ADD refresh_wrapped_dek VARBINARY(MAX);
ALTER TABLE jira_connection ADD refresh_kek_id NVARCHAR(128);

-- Existing OAuth rows hold a refresh token nothing can decrypt. Clearing it is the honest
-- repair: the next sign-in re-consents and stores a usable one, whereas leaving the bytes there
-- means every read of that row throws.
UPDATE jira_connection SET refresh_token_enc = NULL WHERE refresh_token_enc IS NOT NULL;
