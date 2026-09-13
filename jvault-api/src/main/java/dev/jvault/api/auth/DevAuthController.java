package dev.jvault.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * The same status endpoint when Atlassian sign-in is not configured.
 *
 * <p>It answers "already signed in, by a means you cannot use a sign-in screen for", which is
 * true of the development header resolver and keeps the SPA from showing a sign-in button that
 * would go nowhere. It reports {@code DEV} rather than pretending, so the interface can say what
 * is actually holding the door.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "jvault.oauth", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class DevAuthController {

    private final String defaultUser;

    public DevAuthController(@Value("${jvault.dev.default-user:dev-user}") String defaultUser) {
        this.defaultUser = Objects.requireNonNull(defaultUser, "defaultUser");
    }

    @GetMapping("/status")
    public ResponseEntity<AuthController.AuthStatus> status(HttpServletRequest request) {
        return ResponseEntity.ok(new AuthController.AuthStatus(
                true, defaultUser, null, "DEV", null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // Nothing to end. Answering rather than 404ing keeps the client's sign-out path uniform.
        return ResponseEntity.noContent().build();
    }
}
