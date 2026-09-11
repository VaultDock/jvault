package dev.jvault.jira.deployment;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A self-hosted Jira Data Center instance.
 *
 * <p>Requests go straight to the instance's own base URL, and the API is v2 — which means rich
 * text is wiki markup, an unstructured string. That single fact is why section-level {@code SPLIT}
 * is refused here: marker fences in free text break the moment someone edits the description in
 * Jira's own editor, and a broken fence means sensitive text committed to Jira. Failing at
 * configuration time is the only safe answer (docs/05-content-placement.md 5.4).
 *
 * <p>Data Center is not simply the easier target. It offers PKCE, server-side property indexing
 * and usually no enforced rate limits — but its OAuth scopes are coarse enough that least
 * privilege has to come from the service user's Jira permissions instead
 * (docs/13-cloud-vs-datacenter.md).
 */
public final class DataCenterDeployment implements JiraDeployment {

    private final String id;
    private final String baseUrl;
    private final JiraCredentials credentials;

    public DataCenterDeployment(String id, String baseUrl, JiraCredentials credentials) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Kind kind() {
        return Kind.DATA_CENTER;
    }

    @Override
    public URI uri(String apiPath) {
        return URI.create(baseUrl + apiPath);
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
        return "2";
    }

    @Override
    public RichTextFormat richTextFormat() {
        return RichTextFormat.WIKI_MARKUP;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(
                false,  // sectionLevelSplit — refused: wiki markup has no addressable structure
                false,  // dynamicWebhooks — admin-registered instead, and they do not expire
                true,   // jqlSearchableProperties — property indexing is configurable server-side
                false,  // enforcedRateLimits — usually absent by default; do not rely on its absence
                true);  // pkceSupported — verified for incoming application links
    }

    public String baseUrl() {
        return baseUrl;
    }
}
