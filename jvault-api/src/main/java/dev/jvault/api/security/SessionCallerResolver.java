package dev.jvault.api.security;

import dev.jvault.authz.Principal;
import dev.jvault.authz.session.SessionStore;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who is making this request, from the session cookie.
 *
 * <p>The principal is the Atlassian account id rather than an email address, because an address
 * can be reassigned to a different person and an account id cannot. That matters here: the
 * principal is what grants are written against, so a recycled identifier would hand somebody
 * else's access to whoever inherited the mailbox.
 */
public final class SessionCallerResolver implements CallerResolver {

    private final SessionStore sessions;
    private final String deploymentId;
    private final Clock clock;

    public SessionCallerResolver(SessionStore sessions, String deploymentId, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<Caller> resolve(HttpServletRequest request) {
        return sessionIdOf(request)
                .flatMap(sessions::findSession)
                .map(session -> new Caller(
                        Principal.user(session.accountId()),
                        // Groups come from the identity provider in the full design and are
                        // resolved per request rather than frozen into the session. Atlassian's
                        // 3LO does not expose group membership, so this deployment's grants are
                        // to people rather than to groups — stated here so it is not mistaken
                        // for group support that happens to be empty.
                        Set.of(),
                        hasUsableConnection(session.accountId())));
    }

    /**
     * Whether Jira can actually be asked about this person.
     *
     * <p>Drives the "connect Jira" prompt rather than an access decision: the authorization
     * service is what refuses, and it refuses because the live check cannot be made, not because
     * of what this returns.
     */
    private boolean hasUsableConnection(String accountId) {
        return sessions.findConnection(accountId, deploymentId).isPresent();
    }

    private static Optional<String> sessionIdOf(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (var cookie : cookies) {
            if ("jvault_session".equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
