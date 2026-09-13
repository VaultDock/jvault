package dev.jvault.jira.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.jira.deployment.JiraDeployment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The read access the ambiguity protocol needs, over the real Jira API.
 *
 * <p>Two deliberate choices about how the search is bounded.
 *
 * <p><strong>The time window is applied client-side, not in JQL.</strong> JQL's {@code created >=
 * "..."} is interpreted in the *searching user's* configured time zone, which is a different thing
 * from the instant jvault recorded. A protocol whose job is to avoid duplicate tickets should not
 * hinge on two systems agreeing about time zones, so the query bounds by project and creator — both
 * exact — and the window is applied to the {@code created} field that comes back.
 *
 * <p><strong>The result set is capped.</strong> The query asks for the most recent issues by this
 * creator in this project, newest first. That is a handful of rows even on a busy instance, because
 * the creator is the integration identity rather than the whole team.
 */
public final class HttpJiraIssueSearch implements JiraIssueSearch {

    /** Enough to cover a burst of creations in the ambiguity window without an unbounded scan. */
    private static final int MAX_CANDIDATES = 50;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JiraDeployment deployment;
    private final JiraHttpClient http;

    public HttpJiraIssueSearch(JiraDeployment deployment, JiraHttpClient http) {
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public List<IssueCandidate> findCreatedBy(String projectKey,
                                              String creatorIdentity,
                                              Instant from,
                                              Instant to) {
        String jql = "project = " + quote(projectKey)
                + " AND creator = " + creatorClause(creatorIdentity)
                + " ORDER BY created DESC";

        String path = "/rest/api/" + deployment.apiVersion() + "/search/jql"
                + "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                + "&maxResults=" + MAX_CANDIDATES
                + "&fields=summary,created";

        JiraHttpClient.JiraHttpResponse response =
                http.send(new JiraHttpClient.JiraHttpRequest("GET", path, null, Map.of()));

        if (response.status() != 200) {
            // A failed search means "cannot tell", which the resolver treats as inconclusive —
            // never as "the issue was not created". Returning an empty list here would be a lie
            // that leads directly to a duplicate ticket.
            throw new IssueSearchFailedException(
                    "candidate search failed with status " + response.status());
        }

        var candidates = new ArrayList<IssueCandidate>();
        try {
            JsonNode issues = JSON.readTree(response.body()).path("issues");
            for (JsonNode issue : issues) {
                Instant created = parseCreated(issue.at("/fields/created").asText(null));
                if (created == null || created.isBefore(from) || created.isAfter(to)) {
                    continue;
                }
                candidates.add(new IssueCandidate(
                        issue.path("id").asText(null),
                        issue.path("key").asText(null),
                        issue.at("/fields/summary").asText(null),
                        created));
            }
        } catch (Exception e) {
            throw new IssueSearchFailedException("could not read the candidate search response");
        }
        return List.copyOf(candidates);
    }

    @Override
    public Optional<JvaultOrigin> readOrigin(String issueIdOrKey) {
        String path = "/rest/api/" + deployment.apiVersion()
                + "/issue/" + issueIdOrKey + "/properties/jvault.origin";

        JiraHttpClient.JiraHttpResponse response =
                http.send(new JiraHttpClient.JiraHttpRequest("GET", path, null, Map.of()));

        // 404 is the ordinary answer for an issue that is not ours, or one created before the
        // property was written. Both mean "cannot confirm", which is not an error.
        if (response.status() != 200) {
            return Optional.empty();
        }
        try {
            JsonNode value = JSON.readTree(response.body()).path("value");
            return Optional.of(new JvaultOrigin(
                    text(value, "ticketRef"), text(value, "correlationId"), text(value, "channel")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Jira's {@code created} is ISO-8601 with an offset, e.g. {@code 2026-09-13T09:41:12.345+0000}
     * — note the offset has no colon, which {@link Instant#parse} rejects.
     */
    private static Instant parseCreated(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw,
                    java.time.format.DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
        } catch (DateTimeParseException first) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException second) {
                return null;
            }
        }
    }

    /**
     * {@code creator} takes an account id; {@code currentUser()} is the honest fallback when the
     * integration identity has not been resolved to one, since the search runs as that identity
     * anyway.
     */
    private static String creatorClause(String creatorIdentity) {
        if (creatorIdentity == null || creatorIdentity.isBlank()
                || creatorIdentity.startsWith("INTEGRATION:")) {
            return "currentUser()";
        }
        return quote(creatorIdentity);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** The search could not be performed. Distinct from "searched and found nothing". */
    public static class IssueSearchFailedException extends RuntimeException {
        public IssueSearchFailedException(String message) {
            super(message);
        }
    }
}
