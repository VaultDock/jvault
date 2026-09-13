package dev.jvault.api.auth;

import dev.jvault.api.error.ApiProblem;
import dev.jvault.authz.session.SessionStore;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.oauth.JiraCredentialValidator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Signing in with a Jira API token, for deployments that cannot use the OAuth flow.
 *
 * <p>Off unless an administrator turns it on, because it gives up real things and the person
 * typing the token is rarely the person who decided that was acceptable: no per-scope
 * limitation, no revocation by the user, and a credential that person has handled and could
 * paste somewhere else (docs/10-authentication.md 10.6).
 *
 * <p>What it buys is a deployment that works without reaching auth.atlassian.com, which for an
 * air-gapped site behind a forward proxy to one Jira host is not a small thing.
 *
 * <p>The credential is checked against Jira before it is stored. An invalid one rejected here is
 * a typo; the same one accepted and discovered broken at first use is an incident.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "jvault.oauth", name = "allow-manual-token", havingValue = "true")
public class TokenImportController {

    private static final Logger log = LoggerFactory.getLogger(TokenImportController.class);

    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);

    /**
     * How long jvault assumes a manual token lasts.
     *
     * <p>Atlassian API tokens must now expire, between one and 365 days, and the API does not
     * say when. Rather than guess long, the connection is treated as good for a month and
     * re-validated after that — a token revoked in between fails its next permission check and
     * is refused, which is the behaviour that matters.
     */
    private static final Duration ASSUMED_LIFETIME = Duration.ofDays(30);

    private final JiraCredentialValidator validator;
    private final SessionStore sessions;
    private final JiraDeployment deployment;
    private final String siteUrl;
    private final boolean secureCookies;
    private final boolean allowAdministrators;
    private final Clock clock;

    public TokenImportController(JiraCredentialValidator validator,
                                 SessionStore sessions,
                                 JiraDeployment deployment,
                                 @Value("${jvault.jira.base-url:}") String siteUrl,
                                 @Value("${jvault.oauth.secure-cookies:true}")
                                 boolean secureCookies,
                                 @Value("${jvault.oauth.allow-administrator-tokens:false}")
                                 boolean allowAdministrators,
                                 Clock clock) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.siteUrl = Objects.requireNonNull(siteUrl, "siteUrl");
        this.secureCookies = secureCookies;
        this.allowAdministrators = allowAdministrators;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importToken(@RequestBody ImportRequest request,
                                         HttpServletResponse response) {
        if (isBlank(request.email()) || isBlank(request.token())) {
            return refuse(HttpStatus.BAD_REQUEST, "credential-incomplete",
                    "An email address and an API token are both required");
        }

        JiraCredentialValidator.Result identity;
        try {
            identity = validator.validate(siteUrl, request.email().trim(),
                    SensitiveValue.of(request.token().trim(), "jira.apiToken"));
        } catch (JiraCredentialValidator.CredentialRejected e) {
            // The code, never the exception's cause: a failure here has just handled a
            // credential, and the less that travels with it the better.
            log.info("A manually imported credential was rejected: {}", e.code());
            return switch (e.code()) {
                case "INVALID" -> refuse(HttpStatus.UNAUTHORIZED, "credential-invalid",
                        "Jira did not accept that email and token");
                case "UNREACHABLE" -> refuse(HttpStatus.SERVICE_UNAVAILABLE,
                        "jira-unreachable", "Jira could not be reached to check the credential");
                default -> refuse(HttpStatus.UNAUTHORIZED, "credential-invalid",
                        "That credential could not be used");
            };
        }

        if (identity.administrator() && !allowAdministrators) {
            // A credential with administrator rights can read every project, which makes its
            // intersection with jvault's grants meaningless for whoever holds it.
            log.warn("Refused a manually imported credential for {} because it has Jira "
                    + "administrator rights", identity.accountId());
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "credential-too-powerful",
                    "That account has Jira administrator rights, which jvault will not hold a "
                            + "credential for");
        }

        Instant now = clock.instant();
        sessions.saveConnection(new SessionStore.Connection(
                identity.accountId(), deployment.id(), null, siteUrl,
                request.email().trim(), SessionStore.Connection.Kind.MANUAL_TOKEN,
                SensitiveValue.of(request.token().trim(), "jira.apiToken"),
                null, now.plus(ASSUMED_LIFETIME), "basic", "MANUAL_TOKEN"));

        String sessionId = UUID.randomUUID().toString();
        sessions.createSession(new SessionStore.Session(sessionId, identity.accountId(),
                identity.displayName(), identity.email(), identity.locale(), now,
                now.plus(SESSION_LIFETIME)));

        var cookie = new Cookie(AuthController.SESSION_COOKIE, sessionId);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setPath("/");
        cookie.setMaxAge((int) SESSION_LIFETIME.toSeconds());
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        log.info("{} signed in with an imported credential", identity.accountId());

        // The identity it resolved to, never the credential: the UI shows who you are, and has
        // no way to show the token back to you or to anybody reading over your shoulder.
        return ResponseEntity.ok(new ImportResult(identity.displayName(), identity.email()));
    }

    private static ResponseEntity<?> refuse(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(ApiProblem.of(status, code, detail));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ImportRequest(String email, String token) {

        /** Never let a credential reach a log through a careless string interpolation. */
        @Override
        public String toString() {
            return "ImportRequest[email=" + email + ", token=«redacted»]";
        }
    }

    public record ImportResult(String displayName, String email) {
    }
}
