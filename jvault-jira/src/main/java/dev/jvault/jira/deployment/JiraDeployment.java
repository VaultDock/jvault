package dev.jvault.jira.deployment;

import dev.jvault.jira.egress.JiraOperation;

import java.net.URI;
import java.util.Map;

/**
 * The differences between a Jira Cloud site and a Jira Data Center instance, in one place.
 *
 * <p>Decision D1 puts both in scope from the MVP, and the differences are concentrated here
 * rather than smeared across every call site: base URL shape, authentication, API version, and
 * the rich-text format that version implies (docs/13-cloud-vs-datacenter.md 13.2).
 *
 * <p>Everything above this interface — placement, storage, encryption, the outbox, the egress
 * guard — is deployment-agnostic and stays that way.
 */
public interface JiraDeployment {

    String id();

    Kind kind();

    /** Absolute URI for an API path such as {@code /rest/api/3/issue}. */
    URI uri(String apiPath);

    /** Headers every request carries, including authentication. */
    Map<String, String> headers();

    /** {@code 3} on Cloud (ADF), {@code 2} on Data Center (wiki markup). */
    String apiVersion();

    RichTextFormat richTextFormat();

    Capabilities capabilities();

    /** The API path for one operation, given the issue it targets. */
    default String pathFor(JiraOperation operation, String issueIdOrKey) {
        String base = "/rest/api/" + apiVersion();
        return switch (operation) {
            case CREATE_ISSUE -> base + "/issue";
            case UPDATE_FIELDS -> base + "/issue/" + issueIdOrKey;
            case ADD_COMMENT -> base + "/issue/" + issueIdOrKey + "/comment";
            case UPSERT_REMOTE_LINK -> base + "/issue/" + issueIdOrKey + "/remotelink";
            case ADD_ATTACHMENT -> base + "/issue/" + issueIdOrKey + "/attachments";
            case SET_PROPERTY -> base + "/issue/" + issueIdOrKey + "/properties/jvault.origin";
            default -> base + "/issue/" + issueIdOrKey;
        };
    }

    enum Kind {CLOUD, DATA_CENTER}

    enum RichTextFormat {
        /** Atlassian Document Format, a JSON tree. Jira Cloud, REST v3. */
        ADF,
        /** Wiki markup, an unstructured string. Jira Data Center, REST v2. */
        WIKI_MARKUP
    }

    /**
     * What this deployment can do, consulted by configuration validation so that a policy which
     * cannot be honoured is refused at save time rather than discovered during an incident.
     *
     * @param sectionLevelSplit   false on Data Center: wiki markup has no addressable structure,
     *                            and marker fences break the moment someone edits the description
     *                            in Jira — which would commit sensitive text (docs 5.4)
     * @param jqlSearchableProperties false unless jvault is installed as a Forge or Connect app,
     *                            which decision D4 rules out. This is why the ambiguity protocol
     *                            scans rather than queries (docs 0.9)
     * @param enforcedRateLimits  true on Cloud, where the per-issue write limit is real and
     *                            documented; typically absent by default on Data Center
     */
    record Capabilities(boolean sectionLevelSplit,
                        boolean dynamicWebhooks,
                        boolean jqlSearchableProperties,
                        boolean enforcedRateLimits,
                        boolean pkceSupported) {
    }
}
