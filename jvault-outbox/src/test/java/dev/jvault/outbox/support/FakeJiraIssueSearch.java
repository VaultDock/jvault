package dev.jvault.outbox.support;

import dev.jvault.jira.gateway.JiraIssueSearch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeJiraIssueSearch implements JiraIssueSearch {

    private final List<IssueCandidate> issues = new ArrayList<>();
    private final Map<String, JvaultOrigin> origins = new HashMap<>();
    private int searchCalls;
    private RuntimeException failure;

    public FakeJiraIssueSearch withIssue(String issueId, String issueKey, String summary, Instant created) {
        issues.add(new IssueCandidate(issueId, issueKey, summary, created));
        return this;
    }

    public FakeJiraIssueSearch withOrigin(String issueId, String ticketRef, String correlationId) {
        origins.put(issueId, new JvaultOrigin(ticketRef, correlationId, "KAFKA"));
        return this;
    }

    /** Makes the search fail, which must read as "cannot tell" rather than "not created". */
    public FakeJiraIssueSearch failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public List<IssueCandidate> findCreatedBy(String projectKey, String creatorIdentity,
                                              Instant from, Instant to) {
        searchCalls++;
        if (failure != null) {
            throw failure;
        }
        return issues.stream()
                .filter(i -> !i.created().isBefore(from) && !i.created().isAfter(to))
                .toList();
    }

    @Override
    public Optional<JvaultOrigin> readOrigin(String issueIdOrKey) {
        return Optional.ofNullable(origins.get(issueIdOrKey));
    }

    public int searchCalls() {
        return searchCalls;
    }
}
