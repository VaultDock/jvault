package dev.jvault.jira.deployment;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A Jira Cloud site.
 *
 * <p>Requests go to the Atlassian API gateway rather than to the site's own hostname:
 * {@code https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/…}. The {@code cloudId} comes from
 * the accessible-resources call made when a user connects, which is also where site selection
 * happens for a user with access to several sites
 * (docs/00-verified-capabilities.md 0.1).
 *
 * <p>API v3 means rich text is ADF. That is the more demanding format of the two, which is why
 * ADF is jvault's internal canonical form and wiki markup is the lossy projection.
 */
public final class CloudDeployment implements JiraDeployment {

    private static final String GATEWAY = "https://api.atlassian.com/ex/jira/";

    private final String id;
    private final String cloudId;
    private final JiraCredentials credentials;
    private final String gatewayBaseUrl;

    public CloudDeployment(String id, String cloudId, JiraCredentials credentials) {
        this(id, cloudId, credentials, GATEWAY + cloudId);
    }

    /**
     * @param gatewayBaseUrl overrides the Atlassian gateway host. Two real uses: a test stub, and
     *                       an on-premises deployment reaching Atlassian through a forward proxy
     *                       — which is exactly what reading (a) of the open connectivity question
     *                       requires (docs/decisions.md). The path shape is unchanged either way.
     */
    public CloudDeployment(String id, String cloudId, JiraCredentials credentials,
                           String gatewayBaseUrl) {
        this.id = Objects.requireNonNull(id, "id");
        this.cloudId = Objects.requireNonNull(cloudId, "cloudId");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(gatewayBaseUrl, "gatewayBaseUrl");
        this.gatewayBaseUrl = gatewayBaseUrl.endsWith("/")
                ? gatewayBaseUrl.substring(0, gatewayBaseUrl.length() - 1)
                : gatewayBaseUrl;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Kind kind() {
        return Kind.CLOUD;
    }

    @Override
    public URI uri(String apiPath) {
        return URI.create(gatewayBaseUrl + apiPath);
    }

    @Override
    public Map<String, String> headers() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.putAll(credentials.authorizationHeaders());
        return headers;
    }

    @Override
    public String apiVersion() {
        return "3";
    }

    @Override
    public RichTextFormat richTextFormat() {
        return RichTextFormat.ADF;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(
                true,   // sectionLevelSplit — ADF is a tree, so sections are addressable
                true,   // dynamicWebhooks — 30-day expiry, 5 per user per tenant
                false,  // jqlSearchableProperties — needs a Forge/Connect app, ruled out by D4
                true,   // enforcedRateLimits — points, burst, and 20 writes per 2 s per issue
                false); // pkceSupported — not exposed for Cloud 3LO (ECO-283)
    }

    public String cloudId() {
        return cloudId;
    }
}
