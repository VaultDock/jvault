package dev.jvault.api.security;

import dev.jvault.authz.Principal;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Identity from request headers. <strong>Development only.</strong>
 *
 * <p>It trusts whatever the caller says they are, which is exactly what an authentication layer
 * must not do. It exists so that the application can run end to end before the OIDC resolver
 * lands (docs/08-authentication.md), and it refuses to start unless something has explicitly
 * enabled it — a mode this permissive should not be reachable by forgetting a setting.
 *
 * <p>The real resolver validates a bearer token against the identity provider and reads the
 * subject and groups from the verified claims. Nothing downstream changes when it arrives: every
 * consumer of {@link Caller} is already written against this interface rather than against
 * headers.
 */
public final class HeaderCallerResolver implements CallerResolver {

    public static final String USER_HEADER = "X-Jvault-Dev-User";
    public static final String GROUPS_HEADER = "X-Jvault-Dev-Groups";

    private final String defaultUser;

    /**
     * @param defaultUser the identity for a request with no header, so that a browser opening the
     *                    SPA is somebody. Never a fallback in a deployment that has real
     *                    authentication, because there this class is not constructed at all
     */
    public HeaderCallerResolver(String defaultUser) {
        this.defaultUser = Objects.requireNonNull(defaultUser, "defaultUser");
    }

    @Override
    public Optional<Caller> resolve(HttpServletRequest request) {
        String user = header(request, USER_HEADER, defaultUser);
        if (user.isBlank()) {
            return Optional.empty();
        }

        var groups = new LinkedHashSet<Principal>();
        for (String group : header(request, GROUPS_HEADER, "").split(",")) {
            if (!group.isBlank()) {
                groups.add(Principal.group(group.trim()));
            }
        }

        // Jira is reached through the service account in this mode (decision D4), so a caller
        // here has a Jira connection in the only sense that matters to the content check.
        return Optional.of(new Caller(Principal.user(user), Set.copyOf(groups), true));
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Named so that it shows up in a log line and a stack trace for what it is. */
    public static HeaderCallerResolver forDevelopmentOnly(String defaultUser) {
        return new HeaderCallerResolver(defaultUser);
    }

    @Override
    public String toString() {
        return "HeaderCallerResolver[DEVELOPMENT ONLY, trusts " + Arrays.toString(
                new String[]{USER_HEADER, GROUPS_HEADER}) + "]";
    }
}
