package dev.jvault.persistence;

import dev.jvault.authz.session.SessionStore;
import dev.jvault.crypto.text.SensitiveTextCipher;
import dev.jvault.domain.common.SensitiveValue;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Sessions and Jira authorizations on Spring JDBC.
 *
 * <p>The tokens are credentials for somebody's Jira account: a readable access token is that
 * person's Jira access, and a readable refresh token is that access renewed indefinitely. They
 * go through the same envelope the content uses, bound to the row that holds them, so a token
 * lifted into another row does not decrypt.
 */
public final class JdbcSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;
    private final SensitiveTextCipher cipher;
    private final String keyRing;

    public JdbcSessionStore(DataSource dataSource, SensitiveTextCipher cipher, String keyRing) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
    }

    @Override
    public void rememberState(String stateId, String redirectTo, Instant expiresAt) {
        jdbc.update("INSERT INTO auth_state (state_id, redirect_to, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?)",
                stateId, redirectTo, Timestamp.from(Instant.now()), Timestamp.from(expiresAt));
    }

    @Override
    public Optional<String> redeemState(String stateId) {
        List<String> found = jdbc.query(
                "SELECT redirect_to FROM auth_state WHERE state_id = ? AND expires_at > ?",
                (rs, rowNum) -> {
                    String value = rs.getString("redirect_to");
                    return value == null ? "" : value;
                },
                stateId, Timestamp.from(Instant.now()));

        // Deleted rather than marked: replaying a callback must not work a second time, and a
        // flag somebody forgets to check is not a defence.
        int deleted = jdbc.update("DELETE FROM auth_state WHERE state_id = ?", stateId);

        return found.isEmpty() || deleted == 0 ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void createSession(Session session) {
        jdbc.update("""
                        INSERT INTO user_session (
                            session_id, account_id, display_name, email, locale,
                            created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                session.sessionId(), session.accountId(), session.displayName(), session.email(),
                session.locale(), Timestamp.from(session.createdAt()),
                Timestamp.from(session.expiresAt()));
    }

    @Override
    public Optional<Session> findSession(String sessionId) {
        List<Session> found = jdbc.query("""
                        SELECT session_id, account_id, display_name, email, locale,
                               created_at, expires_at
                        FROM user_session WHERE session_id = ? AND expires_at > ?""",
                (rs, rowNum) -> new Session(
                        rs.getString("session_id").trim(),
                        rs.getString("account_id"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("locale"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()),
                sessionId, Timestamp.from(Instant.now()));

        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void endSession(String sessionId) {
        jdbc.update("DELETE FROM user_session WHERE session_id = ?", sessionId);
    }

    @Override
    public void saveConnection(Connection connection) {
        // One envelope for both tokens: they belong to the same authorization and are revoked
        // together, so splitting them across two keys would add ceremony and no isolation.
        String binding = connection.accountId() + "|" + connection.deploymentId();
        SensitiveTextCipher.Sealed access =
                cipher.seal(keyRing, connection.accessToken(), binding);
        byte[] refresh = connection.refreshToken() == null ? null
                : cipher.seal(keyRing, connection.refreshToken(), binding).ciphertext();

        int updated = jdbc.update("""
                        UPDATE jira_connection SET
                            cloud_id = ?, site_url = ?, key_ring = ?, kek_id = ?, wrapped_dek = ?,
                            access_token_enc = ?, refresh_token_enc = ?, access_expires_at = ?,
                            scopes = ?, flow_used = ?, updated_at = ?
                        WHERE account_id = ? AND deployment_id = ?""",
                connection.cloudId(), connection.siteUrl(), keyRing, access.kekId(),
                access.wrappedKey(), access.ciphertext(), refresh,
                Timestamp.from(connection.accessExpiresAt()), connection.grantedScopes(),
                connection.flowUsed(), Timestamp.from(Instant.now()),
                connection.accountId(), connection.deploymentId());

        if (updated == 0) {
            insertConnection(connection, access, refresh);
        }
    }

    private void insertConnection(Connection connection, SensitiveTextCipher.Sealed access,
                                  byte[] refresh) {
        try {
            jdbc.update("""
                            INSERT INTO jira_connection (
                                account_id, deployment_id, cloud_id, site_url, key_ring, kek_id,
                                wrapped_dek, access_token_enc, refresh_token_enc,
                                access_expires_at, scopes, flow_used, connected_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    connection.accountId(), connection.deploymentId(), connection.cloudId(),
                    connection.siteUrl(), keyRing, access.kekId(), access.wrappedKey(),
                    access.ciphertext(), refresh, Timestamp.from(connection.accessExpiresAt()),
                    connection.grantedScopes(), connection.flowUsed(),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException e) {
            // Two tabs finishing consent at once. The other one's tokens are as good as these.
            saveConnection(connection);
        }
    }

    @Override
    public Optional<Connection> findConnection(String accountId, String deploymentId) {
        String binding = accountId + "|" + deploymentId;

        List<Connection> found = jdbc.query("""
                        SELECT account_id, deployment_id, cloud_id, site_url, key_ring, kek_id,
                               wrapped_dek, access_token_enc, refresh_token_enc,
                               access_expires_at, scopes, flow_used
                        FROM jira_connection WHERE account_id = ? AND deployment_id = ?""",
                (rs, rowNum) -> {
                    String ring = rs.getString("key_ring");
                    String kekId = rs.getString("kek_id");
                    byte[] wrapped = rs.getBytes("wrapped_dek");
                    byte[] refreshBytes = rs.getBytes("refresh_token_enc");

                    return new Connection(
                            rs.getString("account_id"),
                            rs.getString("deployment_id"),
                            rs.getString("cloud_id"),
                            rs.getString("site_url"),
                            open(ring, kekId, wrapped, rs.getBytes("access_token_enc"), binding,
                                    "jira.accessToken"),
                            refreshBytes == null ? null
                                    : open(ring, kekId, wrapped, refreshBytes, binding,
                                            "jira.refreshToken"),
                            rs.getTimestamp("access_expires_at").toInstant(),
                            rs.getString("scopes"),
                            rs.getString("flow_used"));
                },
                accountId, deploymentId);

        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private SensitiveValue open(String ring, String kekId, byte[] wrapped, byte[] ciphertext,
                                String binding, String label) {
        var sealed = new SensitiveTextCipher.Sealed(kekId, wrapped, ciphertext);
        return SensitiveValue.of(
                new String(cipher.openBytes(ring, sealed, binding), StandardCharsets.UTF_8), label);
    }

    @Override
    public int purgeExpired(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        return jdbc.update("DELETE FROM auth_state WHERE expires_at < ?", cutoff)
                + jdbc.update("DELETE FROM user_session WHERE expires_at < ?", cutoff);
    }

    @SuppressWarnings("unused")
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
