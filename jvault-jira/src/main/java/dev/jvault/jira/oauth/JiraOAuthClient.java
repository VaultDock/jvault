package dev.jvault.jira.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.domain.common.SensitiveValue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Atlassian's OAuth 2.0 three-legged flow.
 *
 * <p>What the user gets from completing it is a token carrying <em>their</em> Jira permissions,
 * which is the whole point: it is what lets jvault ask "may this person see this issue" and get
 * Jira's own answer rather than a guess. The client id identifies jvault and grants nothing on
 * its own (docs/10-authentication.md 10.2).
 *
 * <p>No PKCE. Atlassian does not expose it for Cloud 3LO (docs/00-verified-capabilities.md 0.1),
 * so the {@code state} parameter carries the whole weight of tying a callback to the browser
 * that started it. That absence is recorded against each connection rather than glossed over,
 * because it is the sort of thing a reviewer should be able to see.
 */
public final class JiraOAuthClient {

    private static final String AUTHORIZE = "https://auth.atlassian.com/authorize";
    private static final String TOKEN = "https://auth.atlassian.com/oauth/token";
    private static final String RESOURCES = "https://api.atlassian.com/oauth/token/accessible-resources";
    private static final String ME = "https://api.atlassian.com/me";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Least privilege, and granular where Atlassian offers it.
     *
     * <p>Read-only on purpose. jvault writes to Jira as the service account (decision D4), so a
     * user's token is only ever used to answer permission questions and to read metadata in
     * their own language. Asking for write here would request a capability nothing uses, which
     * is the kind of thing that fails a review for good reason.
     */
    private static final List<String> SCOPES = List.of(
            "read:jira-work",
            "read:jira-user",
            "offline_access");

    private final String clientId;
    private final SensitiveValue clientSecret;
    private final String redirectUri;
    private final HttpClient http;

    public JiraOAuthClient(String clientId, SensitiveValue clientSecret, String redirectUri) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.redirectUri = Objects.requireNonNull(redirectUri, "redirectUri");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Where to send the browser. {@code state} ties the eventual callback back to this session. */
    public String authorizationUrl(String state) {
        return AUTHORIZE
                + "?audience=api.atlassian.com"
                + "&client_id=" + encode(clientId)
                + "&scope=" + encode(String.join(" ", SCOPES))
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(state)
                + "&response_type=code"
                // Atlassian issues a refresh token only when consent is explicitly offline, and
                // without one the connection dies at the first access-token expiry.
                + "&prompt=consent";
    }

    public Tokens exchangeCode(String code) {
        return tokensFrom(post("""
                {"grant_type":"authorization_code","client_id":"%s","client_secret":"%s",
                 "code":"%s","redirect_uri":"%s"}"""
                .formatted(json(clientId), json(clientSecret.reveal()), json(code),
                        json(redirectUri))));
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>Cloud refresh tokens rotate: each use invalidates the previous one
     * (docs/00-verified-capabilities.md 0.1). Two concurrent refreshes can therefore invalidate
     * each other and leave a connection dead, which is why the caller serialises them rather
     * than treating this as merely an efficiency question.
     */
    public Tokens refresh(SensitiveValue refreshToken) {
        return tokensFrom(post("""
                {"grant_type":"refresh_token","client_id":"%s","client_secret":"%s",
                 "refresh_token":"%s"}"""
                .formatted(json(clientId), json(clientSecret.reveal()),
                        json(refreshToken.reveal()))));
    }

    /** Who the token belongs to. */
    public AtlassianAccount accountOf(SensitiveValue accessToken) {
        JsonNode body = get(ME, accessToken);
        return new AtlassianAccount(
                body.path("account_id").asText(null),
                body.path("name").asText(null),
                body.path("email").asText(null),
                body.path("locale").asText(null));
    }

    /** The Jira sites this authorization reaches. */
    public List<AccessibleSite> accessibleSites(SensitiveValue accessToken) {
        JsonNode body = get(RESOURCES, accessToken);
        var sites = new ArrayList<AccessibleSite>();
        for (JsonNode site : body) {
            sites.add(new AccessibleSite(
                    site.path("id").asText(null),
                    site.path("url").asText(null),
                    site.path("name").asText(null)));
        }
        return List.copyOf(sites);
    }

    private Tokens tokensFrom(JsonNode body) {
        String access = body.path("access_token").asText(null);
        if (access == null) {
            // Atlassian's error body names the client id and sometimes the redirect URI. Neither
            // belongs in an exception that might reach a log aggregator or a user.
            throw new OAuthException("the token exchange did not return an access token");
        }
        return new Tokens(
                SensitiveValue.of(access, "jira.accessToken"),
                body.hasNonNull("refresh_token")
                        ? SensitiveValue.of(body.path("refresh_token").asText(), "jira.refreshToken")
                        : null,
                Instant.now().plusSeconds(Math.max(60, body.path("expires_in").asLong(3600))),
                body.path("scope").asText(""));
    }

    private JsonNode post(String body) {
        var request = HttpRequest.newBuilder(URI.create(TOKEN))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode get(String url, SensitiveValue accessToken) {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken.reveal())
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new OAuthException("Atlassian answered " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("interrupted talking to Atlassian");
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthException("could not reach Atlassian");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Escapes a value for the JSON bodies above, which are assembled rather than serialised. */
    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * @param refreshToken present only when {@code offline_access} was granted. Without it the
     *                     connection ends at {@code accessExpiresAt} and the user must consent
     *                     again
     */
    public record Tokens(SensitiveValue accessToken,
                         SensitiveValue refreshToken,
                         Instant accessExpiresAt,
                         String grantedScopes) {
    }

    public record AtlassianAccount(String accountId, String name, String email, String locale) {
    }

    public record AccessibleSite(String cloudId, String url, String name) {
    }

    public static class OAuthException extends RuntimeException {
        public OAuthException(String message) {
            super(message);
        }
    }
}
