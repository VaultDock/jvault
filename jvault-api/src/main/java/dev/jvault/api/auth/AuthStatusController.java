package dev.jvault.api.auth;

import dev.jvault.authz.session.SessionStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Who is signed in, and what the ways in are.
 *
 * <p>Always present, whichever methods are configured, because the sign-in screen has to ask
 * this before there is a session and a screen offering a button that goes nowhere is worse than
 * one offering nothing.
 *
 * <p>Unauthenticated on purpose, and it says nothing an anonymous caller should not know:
 * whether a session exists, and how one is started here.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthStatusController {

    private final SessionStore sessions;
    private final boolean oauthEnabled;
    private final boolean manualTokenAllowed;
    private final boolean devAuth;
    private final String devUser;

    public AuthStatusController(SessionStore sessions,
                                @Value("${jvault.oauth.enabled:false}") boolean oauthEnabled,
                                @Value("${jvault.oauth.allow-manual-token:false}")
                                boolean manualTokenAllowed,
                                @Value("${jvault.dev.insecure-auth:false}") boolean devAuth,
                                @Value("${jvault.dev.default-user:dev-user}") String devUser) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.oauthEnabled = oauthEnabled;
        this.manualTokenAllowed = manualTokenAllowed;
        this.devAuth = devAuth;
        this.devUser = devUser;
    }

    @GetMapping("/status")
    public ResponseEntity<AuthStatus> status(HttpServletRequest request) {
        Optional<SessionStore.Session> session =
                sessionIdOf(request).flatMap(sessions::findSession);

        if (session.isPresent()) {
            SessionStore.Session active = session.get();
            return ResponseEntity.ok(new AuthStatus(true, active.displayName(), active.email(),
                    List.of(), null));
        }

        var methods = new ArrayList<String>();
        if (oauthEnabled) {
            methods.add("ATLASSIAN");
        }
        if (manualTokenAllowed) {
            methods.add("MANUAL_TOKEN");
        }

        if (methods.isEmpty() && devAuth) {
            // Nothing to sign in with, and a header is being trusted instead. Reported as
            // already-signed-in so the SPA does not offer a sign-in screen with no buttons, and
            // reported as DEV so it can say what is actually holding the door.
            return ResponseEntity.ok(new AuthStatus(true, devUser, null, List.of("DEV"), null));
        }

        return ResponseEntity.ok(new AuthStatus(false, null, null, methods,
                oauthEnabled ? "/api/v1/auth/login" : null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionIdOf(request).ifPresent(sessions::endSession);

        // Cleared whether or not a session was found: a cookie naming a session that no longer
        // exists is only ever a nuisance.
        var cookie = new Cookie(AuthController.SESSION_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.noContent().build();
    }

    static Optional<String> sessionIdOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (AuthController.SESSION_COOKIE.equals(cookie.getName())
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * @param methods the ways in that this deployment actually offers, in the order to show them
     * @param loginUrl where to start the Atlassian flow, when that is one of them
     */
    public record AuthStatus(boolean authenticated,
                             String displayName,
                             String email,
                             List<String> methods,
                             String loginUrl) {
    }
}
