package dev.jvault.content;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * jvault's record of a ticket: the binding between a Jira issue and its external content.
 *
 * <p>{@code jiraFields} holds only values that are safe for Jira — Jira-placed values and
 * rendered surrogates. It is what the payload assembler re-reads at dispatch time, which is how
 * the outbox row itself gets away with holding nothing but identifiers
 * (docs/04-data-model.md 4.3).
 */
public record TicketRecord(String ticketRef,
                           String deploymentId,
                           String projectKey,
                           String issueTypeId,
                           String dedupeKey,
                           String correlationId,
                           TicketCommand.Origin origin,
                           Map<String, String> jiraFields,
                           List<String> externalContentRefs,
                           State state,
                           String jiraIssueId,
                           String jiraIssueKey,
                           Instant createdAt) {

    /** Mirrors {@code ticket_record.state} (docs/04-data-model.md 4.4). */
    public enum State {
        DRAFT, CONTENT_STORED, JIRA_PENDING, AMBIGUOUS, ACTIVE, FAILED
    }

    public TicketRecord {
        Objects.requireNonNull(ticketRef, "ticketRef");
        jiraFields = jiraFields == null ? Map.of() : Map.copyOf(jiraFields);
        externalContentRefs = externalContentRefs == null ? List.of() : List.copyOf(externalContentRefs);
    }

    public TicketRecord withFields(Map<String, String> fields, List<String> contentRefs, State newState) {
        return new TicketRecord(ticketRef, deploymentId, projectKey, issueTypeId, dedupeKey,
                correlationId, origin, fields, contentRefs, newState, jiraIssueId, jiraIssueKey,
                createdAt);
    }

    public String expectedSummary() {
        return jiraFields.get("summary");
    }
}
