package dev.jvault.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.session.SessionStore;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.jira.oauth.JiraOAuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live half of the INTERSECT rule: does this person still have the project in Jira.
 *
 * <p>jvault's own grants say who <em>may</em> read secured content. This says whether Jira still
 * agrees — so revoking someone in Jira revokes them here, without anybody remembering to update
 * a second system. A grant that outlives the Jira permission it was written alongside is the
 * failure mode this exists to prevent.
 *
 * <p>Asked as the user, with their own token, which is why signing in with Atlassian was the
 * prerequisite. Asking as the service account would answer a different question — whether the
 * <em>integration</em> can see the project — and answer it "yes" for everybody.
 */
public final class JiraUserAccessChecker implements ContentAuthorizationService.JiraAccessChecker {

    private static final Logger log = LoggerFactory.getLogger(JiraUserAccessChecker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How long an answer is reused.
     *
     * <p>Short on purpose. This is a revocation path: the gap between losing access in Jira and
     * losing it here is exactly this number, and a permissions call per content read would
     * otherwise put jvault's traffic through Jira's rate limiter.
     */
    private static final Duration CACHE_FOR = Duration.ofMinutes(2);

    private final SessionStore sessions;
    private final JiraOAuthClient oauth;
    private final String deploymentId;
    private final Clock clock;
    private final HttpClient http;
    private final ConcurrentHashMap<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    public JiraUserAccessChecker(SessionStore sessions, JiraOAuthClient oauth,
                                 String deploymentId, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public Access canBrowse(Principal user, Scope scope) {
        String projectKey = projectOf(scope);
        if (projectKey == null) {
            return Access.DENIED;
        }

        String cacheKey = user.externalId() + "|" + projectKey;
        CachedAnswer cached = cache.get(cacheKey);
        Instant now = clock.instant();
        if (cached != null && cached.until().isAfter(now)) {
            return cached.access();
        }

        Access answer = ask(user, projectKey, now);
        // UNAVAILABLE is not cached: it means Jira could not be reached, and caching that would
        // extend an outage past its end.
        if (answer != Access.UNAVAILABLE) {
            cache.put(cacheKey, new CachedAnswer(answer, now.plus(CACHE_FOR)));
        }
        return answer;
    }

    private Access ask(Principal user, String projectKey, Instant now) {
        Optional<SessionStore.Connection> maybe =
                sessions.findConnection(user.externalId(), deploymentId);
        if (maybe.isEmpty()) {
            // Not connected. Denied rather than unavailable: nothing is broken, this person
            // simply has not authorized jvault to ask Jira on their behalf.
            return Access.DENIED;
        }

        SensitiveValue token;
        try {
            token = accessTokenOf(maybe.get(), now);
        } catch (RuntimeException e) {
            log.warn("Could not refresh the Jira token for {}", user.externalId(), e);
            return Access.UNAVAILABLE;
        }

        try {
            SessionStore.Connection connection = maybe.get();
            String url = permissionsUrl(connection, projectKey);

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", authorizationFor(connection, token))
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                // Jira's answer, not a fault: this person cannot see this project.
                return Access.DENIED;
            }
            if (response.statusCode() / 100 != 2) {
                return Access.UNAVAILABLE;
            }

            JsonNode permission = JSON.readTree(response.body())
                    .path("permissions").path("BROWSE_PROJECTS");
            return permission.path("havePermission").asBoolean(false)
                    ? Access.ALLOWED
                    : Access.DENIED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Access.UNAVAILABLE;
        } catch (Exception e) {
            // Unreachable, not refused. The distinction matters: the authorization service holds
            // a degraded-mode decision for one and a plain refusal for the other.
            log.warn("Could not ask Jira about {} on {}", user.externalId(), projectKey, e);
            return Access.UNAVAILABLE;
        }
    }

    /**
     * Where to ask.
     *
     * <p>An OAuth token is presented to Atlassian's gateway against a cloud id; a manually
     * imported credential belongs to the site itself and is presented there. Sending either to
     * the other's host produces a 401 that looks exactly like a revoked permission.
     */
    private static String permissionsUrl(SessionStore.Connection connection, String projectKey) {
        String query = "/rest/api/3/mypermissions?permissions=BROWSE_PROJECTS&projectKey="
                + URLEncoder.encode(projectKey, StandardCharsets.UTF_8);

        return connection.kind() == SessionStore.Connection.Kind.MANUAL_TOKEN
                ? trimTrailingSlash(connection.siteUrl()) + query
                : "https://api.atlassian.com/ex/jira/" + connection.cloudId() + query;
    }

    private static String authorizationFor(SessionStore.Connection connection,
                                           SensitiveValue token) {
        if (connection.kind() == SessionStore.Connection.Kind.MANUAL_TOKEN) {
            // Basic, with the email: an Atlassian API token is half a credential on its own.
            return "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (connection.authEmail() + ":" + token.reveal())
                            .getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + token.reveal();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * A usable access token, refreshing if it has expired.
     *
     * <p>Serialised per connection. Cloud refresh tokens rotate — each use invalidates the
     * previous one — so two concurrent refreshes can invalidate each other and leave the
     * connection dead (docs/10-authentication.md 10.3). A lock on this instance is enough for
     * one node; a second node needs the advisory lock the design specifies, and does not have it
     * yet.
     */
    private SensitiveValue accessTokenOf(SessionStore.Connection connection, Instant now) {
        if (connection.isFresh(now)) {
            return connection.accessToken();
        }
        if (!connection.isRenewable()) {
            // A manually imported token cannot be renewed on the user's behalf; somebody has to
            // import a new one. Saying so beats a refresh call that was never going to work.
            throw new IllegalStateException("the connection has expired and cannot be renewed");
        }

        synchronized (this) {
            // Re-read under the lock: another request may have refreshed while this one waited,
            // and using the token it replaced would invalidate the new one.
            SessionStore.Connection current = sessions
                    .findConnection(connection.accountId(), connection.deploymentId())
                    .orElse(connection);
            if (current.isFresh(clock.instant())) {
                return current.accessToken();
            }

            JiraOAuthClient.Tokens refreshed = oauth.refresh(current.refreshToken());
            var updated = new SessionStore.Connection(current.accountId(),
                    current.deploymentId(), current.cloudId(), current.siteUrl(),
                    current.authEmail(), current.kind(),
                    refreshed.accessToken(),
                    refreshed.refreshToken() == null ? current.refreshToken()
                            : refreshed.refreshToken(),
                    refreshed.accessExpiresAt(), refreshed.grantedScopes(), current.flowUsed());
            sessions.saveConnection(updated);
            return updated.accessToken();
        }
    }

    @Override
    public boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since) {
        // Degraded mode: was this person allowed recently enough to keep letting them in while
        // Jira is unreachable. Only a cached ALLOW counts, and only within its own window —
        // extending it would turn a Jira outage into a period where revocation does not work.
        CachedAnswer cached = cache.get(user.externalId() + "|" + projectOf(scope));
        return cached != null && cached.access() == Access.ALLOWED
                && cached.until().isAfter(since);
    }

    /** A space id is {@code deployment/PROJECT}; the project is what Jira understands. */
    private static String projectOf(Scope scope) {
        String spaceId = scope.spaceScope().id();
        int slash = spaceId.lastIndexOf('/');
        return slash < 0 ? spaceId : spaceId.substring(slash + 1);
    }

    private record CachedAnswer(Access access, Instant until) {
    }
}
