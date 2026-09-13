package dev.jvault.authz.session;

import dev.jvault.domain.common.SensitiveValue;

import java.time.Instant;
import java.util.Optional;

/**
 * Where sessions and Jira authorizations live.
 *
 * <p>A port rather than a class because the tokens it holds are the most sensitive thing in the
 * system after the content keys, and a test that needs a session should not be able to reach the
 * production one by accident.
 *
 * <p>It sits beside the authorization model rather than in the web layer: a Jira connection is
 * what the live half of the INTERSECT rule needs in order to ask Jira anything, so the thing
 * that owns authorization owns the port that feeds it.
 */
public interface SessionStore {

    /** Records the OAuth state, single-use, so a callback can be tied to the request it answers. */
    void rememberState(String stateId, String redirectTo, Instant expiresAt);

    /**
     * Consumes a state value.
     *
     * <p>Returns empty if it was never issued, has expired, or has already been redeemed. The
     * deletion is the point: replaying a callback must not work twice.
     */
    Optional<String> redeemState(String stateId);

    void createSession(Session session);

    Optional<Session> findSession(String sessionId);

    void endSession(String sessionId);

    void saveConnection(Connection connection);

    Optional<Connection> findConnection(String accountId, String deploymentId);

    /** Removes expired sessions and states. Housekeeping, not security-critical on its own. */
    int purgeExpired(Instant now);

    record Session(String sessionId,
                   String accountId,
                   String displayName,
                   String email,
                   String locale,
                   Instant createdAt,
                   Instant expiresAt) {
    }

    /**
     * One person's Jira authorization.
     *
     * @param flowUsed what protections this connection actually had. Cloud 3LO has no PKCE and
     *                 Data Center does, so recording it makes the difference reviewable rather
     *                 than a silent downgrade (docs/10-authentication.md 10.3)
     */
    record Connection(String accountId,
                      String deploymentId,
                      String cloudId,
                      String siteUrl,
                      SensitiveValue accessToken,
                      SensitiveValue refreshToken,
                      Instant accessExpiresAt,
                      String grantedScopes,
                      String flowUsed) {

        public boolean isFresh(Instant now) {
            // A minute of headroom: a token that expires while the request is in flight is a
            // failure that looks like a permissions problem.
            return accessExpiresAt.isAfter(now.plusSeconds(60));
        }
    }
}
