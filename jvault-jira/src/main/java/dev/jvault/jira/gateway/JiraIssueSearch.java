package dev.jvault.jira.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The narrow read access the ambiguity protocol needs (docs/12-reliability.md 12.4.2).
 *
 * <p>Deliberately not a general Jira read API. These two operations exist to answer one
 * question — "did the issue I may have just created actually get created?" — and keeping the
 * port that small makes it obvious when something else starts depending on it.
 *
 * <p>Note what is <em>not</em> here: a property-based JQL search. On Jira Cloud, entity
 * properties are only JQL-searchable if the integration is distributed as a Forge or Connect
 * app, which decision D4 rules out (docs/00-verified-capabilities.md 0.9). So the protocol
 * searches on ordinary JQL fields and then fetches properties per candidate.
 */
public interface JiraIssueSearch {

    /**
     * Issues in a project created by a given identity inside a time window.
     *
     * <p>Bounded by construction: the window is a few minutes wide and the identity is the
     * integration account, so this returns a handful of rows even on a busy instance.
     */
    List<IssueCandidate> findCreatedBy(String projectKey,
                                       String creatorIdentity,
                                       Instant from,
                                       Instant to);

    /**
     * Reads the {@code jvault.origin} issue property.
     *
     * <p>Returns empty both when the property is absent and when the issue is gone; the
     * protocol treats those the same way, because in both cases this candidate cannot be
     * confirmed as ours.
     */
    Optional<JvaultOrigin> readOrigin(String issueIdOrKey);

    /**
     * @param summary the Jira summary, used only for the heuristic fallback match. It is a
     *                surrogate or a Jira-placed value, so it is never sensitive.
     */
    record IssueCandidate(String issueId, String issueKey, String summary, Instant created) {
    }

    /** The contents of the {@code jvault.origin} property. Parsing JSON is the adapter's job. */
    record JvaultOrigin(String ticketRef, String correlationId, String channel) {
    }
}
