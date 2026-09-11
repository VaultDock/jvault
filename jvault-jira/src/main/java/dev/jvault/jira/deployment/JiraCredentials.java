package dev.jvault.jira.deployment;

import java.util.Map;

/**
 * Supplies the authentication header for a Jira request.
 *
 * <p>A supplier rather than a stored string, because the interesting case is a bearer token that
 * expires: the header must be fetched at request time so a refresh that happened a moment ago is
 * picked up. Implementations are expected to be cheap and to have refreshed already — this is not
 * the place to block on a token exchange.
 *
 * <p>Nothing here holds a credential in a field that could be printed. Implementations must not
 * override {@code toString} to reveal one, and must never log the value they return.
 */
@FunctionalInterface
public interface JiraCredentials {

    Map<String, String> authorizationHeaders();

    /** OAuth 2.0 access token, or a Data Center personal access token. */
    static JiraCredentials bearer(java.util.function.Supplier<String> token) {
        return () -> Map.of("Authorization", "Bearer " + token.get());
    }

    /**
     * Atlassian Cloud basic auth: account e-mail plus an API token.
     *
     * <p>The administrator-enabled alternative to OAuth, not the standard path
     * (docs/10-authentication.md 10.6). Atlassian now requires these tokens to expire within a
     * year, so a deployment using this needs rotation alerting.
     */
    static JiraCredentials basic(String email, java.util.function.Supplier<String> apiToken) {
        return () -> {
            String raw = email + ":" + apiToken.get();
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Map.of("Authorization", "Basic " + encoded);
        };
    }

    static JiraCredentials none() {
        return Map::of;
    }
}
