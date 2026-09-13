package dev.jvault.content;

import java.util.Objects;

/**
 * Builds the permanent link written into Jira.
 *
 * <p>Three properties the design requires of it, all visible in the shape of the URL
 * (docs/01-requirements.md FR-LNK-1, FR-LNK-2):
 *
 * <ul>
 *   <li><strong>Stable.</strong> The only variable is {@code contentRef}, which never changes —
 *       not when content is edited, versioned, migrated to another backend, or archived.</li>
 *   <li><strong>Not a capability.</strong> There is no token, signature or expiry in it. The link
 *       names content; it does not grant access to it. Possession proves nothing and every
 *       request is authorized afresh.</li>
 *   <li><strong>Opaque.</strong> No filename, no project, no classification — a URL that leaks
 *       through a referrer header, a chat paste or a screenshot should reveal nothing.</li>
 * </ul>
 *
 * <p>The path is deliberately outside {@code /api/v1}: it is a permanent public identifier by
 * contract, so it must not be versioned along with the API.
 */
public final class LinkFactory {

    private final String baseUrl;

    public LinkFactory(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String linkTo(String contentRef) {
        return baseUrl + "/c/" + contentRef;
    }

    /**
     * The ticket as a whole: every part somebody is allowed to see, in one place.
     *
     * <p>Jira gets this one rather than a link per part. Someone reading an issue wants "show me
     * what is missing from this", not a row of indistinguishable content references — and a
     * ticket with four secured fields would otherwise put four links on the issue.
     *
     * <p>Not a capability, like every other link jvault emits: following it is authorized afresh,
     * so a link that reaches the wrong person still shows them nothing.
     */
    public String linkToTicket(String ticketRef) {
        return baseUrl + "/t/" + ticketRef;
    }
}
