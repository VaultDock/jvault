package dev.jvault.api.auth;

import dev.jvault.api.error.ApiProblem;
import dev.jvault.authz.session.SessionStore;
import dev.jvault.jira.oauth.JiraOAuthClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing in with Atlassian.
 *
 * <p>A backend-for-frontend: the browser gets an opaque session cookie and the tokens never leave
 * this process (docs/10-authentication.md 10.1). The user never sees, copies or pastes a token,
 * which is the entire point of doing the code exchange server-side.
 *
 * <p>What the resulting authorization is <em>for</em> is worth being precise about. jvault still
 * writes to Jira as the service account (decision D4), so this token is never used to create
 * anything. It is used to ask Jira whether this person may see an issue — the live half of the
 * INTERSECT rule — and to read metadata in their own language.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "jvault.oauth", name = "enabled", havingValue = "true")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    public static final String SESSION_COOKIE = "jvault_session";

    /** Long enough not to interrupt a working day, short enough that a stolen cookie expires. */
    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);

    /** The window between clicking sign in and returning from Atlassian. */
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);

    private final JiraOAuthClient oauth;
    private final SessionStore sessions;
    private final String deploymentId;
    private final String appBaseUrl;
    private final boolean secureCookies;
    private final Clock clock;

    public AuthController(JiraOAuthClient oauth,
                          SessionStore sessions,
                          String deploymentId,
                          @Value("${jvault.oauth.app-base-url:http://localhost:5173}")
                          String appBaseUrl,
                          @Value("${jvault.oauth.secure-cookies:true}")
                          boolean secureCookies,
                          Clock clock) {
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        this.appBaseUrl = Objects.requireNonNull(appBaseUrl, "appBaseUrl");
        this.secureCookies = secureCookies;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login(@RequestParam(defaultValue = "/") String next) {
        String state = UUID.randomUUID().toString();
        sessions.rememberState(state, safeRedirect(next), clock.instant().plus(STATE_LIFETIME));

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauth.authorizationUrl(state)))
                .build();
    }

    /**
     * Where Atlassian sends the browser back.
     *
     * <p>The state check is the whole defence: Atlassian does not expose PKCE for Cloud 3LO, so
     * without it an attacker could have their own authorization code redeemed into somebody
     * else's browser. It is single-use and time-bounded for that reason.
     */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error,
                                      HttpServletResponse response) {
        if (error != null) {
            // The user declined, or Atlassian refused. Neither is a fault worth a stack trace.
            log.info("Atlassian returned an authorization error");
            return redirectTo("/?signin=declined");
        }
        if (code == null || state == null) {
            return ResponseEntity.badRequest().body(ApiProblem.of(
                    HttpStatus.BAD_REQUEST, "invalid-callback", "The callback was incomplete"));
        }

        Optional<String> redirectTo = sessions.redeemState(state);
        if (redirectTo.isEmpty()) {
            // Unknown, expired, or already used. All three mean the same thing here.
            log.warn("An authorization callback arrived with a state that was not outstanding");
            return redirectTo("/?signin=expired");
        }

        JiraOAuthClient.Tokens tokens;
        JiraOAuthClient.AtlassianAccount account;
        List<JiraOAuthClient.AccessibleSite> sites;
        try {
            tokens = oauth.exchangeCode(code);
            account = oauth.accountOf(tokens.accessToken());
            sites = oauth.accessibleSites(tokens.accessToken());
        } catch (JiraOAuthClient.OAuthException e) {
            // This arrives in a browser following a redirect, so it has to end at a page rather
            // than as a problem document nobody can act on. The usual causes are a client secret
            // that does not match the client id and a redirect URI that does not match the one
            // registered — both configuration, both invisible from the response.
            log.error("The Atlassian token exchange failed. Check that the client secret matches "
                    + "the client id, and that the callback registered in the developer console "
                    + "is exactly the configured redirect URI.", e);
            return redirectTo("/?signin=exchange");
        }
        if (sites.isEmpty()) {
            // Consent succeeded and reaches no Jira. Saying so beats a session that fails later
            // on every permission check for reasons nobody can see.
            log.warn("Account {} authorized jvault but can reach no Jira site",
                    account.accountId());
            return redirectTo("/?signin=nosites");
        }
        JiraOAuthClient.AccessibleSite site = sites.get(0);

        sessions.saveConnection(new SessionStore.Connection(
                account.accountId(), deploymentId, site.cloudId(), site.url(),
                null, SessionStore.Connection.Kind.OAUTH,
                tokens.accessToken(), tokens.refreshToken(), tokens.accessExpiresAt(),
                tokens.grantedScopes(),
                // Recorded rather than assumed, so the absence of PKCE on Cloud is a reviewable
                // fact rather than a silent downgrade.
                "OAUTH_3LO_NO_PKCE"));

        String sessionId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        sessions.createSession(new SessionStore.Session(sessionId, account.accountId(),
                account.name(), account.email(), account.locale(), now,
                now.plus(SESSION_LIFETIME)));

        response.addCookie(sessionCookie(sessionId, (int) SESSION_LIFETIME.toSeconds()));
        return redirectTo(redirectTo.get());
    }

    private Cookie sessionCookie(String value, int maxAge) {
        var cookie = new Cookie(SESSION_COOKIE, value);
        // HttpOnly: script must not be able to read it, which is the difference between one
        // cross-site scripting bug and every session in the building.
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    private ResponseEntity<Void> redirectTo(String path) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(appBaseUrl + safeRedirect(path)))
                .build();
    }

    /**
     * Only paths within this application.
     *
     * <p>An open redirect on a sign-in callback is how a phishing page borrows somebody else's
     * domain for the part of the flow the user actually looks at.
     */
    private static String safeRedirect(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//")) {
            return "/";
        }
        return next;
    }
}
