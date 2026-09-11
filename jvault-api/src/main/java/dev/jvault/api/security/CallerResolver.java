package dev.jvault.api.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Establishes who is calling.
 *
 * <p>A port, because the production implementation is the OIDC backend-for-frontend — session
 * cookie for browsers, bearer token from the enterprise identity provider for machine clients —
 * and none of that should be entangled with the endpoints themselves
 * (docs/10-authentication.md 10.1).
 *
 * <p>An empty result means anonymous, which every protected endpoint turns into a 401.
 */
public interface CallerResolver {

    Optional<Caller> resolve(HttpServletRequest request);
}
