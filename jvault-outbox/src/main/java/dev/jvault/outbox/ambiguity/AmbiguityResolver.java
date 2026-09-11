package dev.jvault.outbox.ambiguity;

import dev.jvault.jira.gateway.JiraIssueSearch;
import dev.jvault.jira.gateway.JiraIssueSearch.IssueCandidate;
import dev.jvault.outbox.TicketStateSink.AmbiguityContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a Jira issue whose creation timed out actually exists
 * (docs/12-reliability.md 12.4.2).
 *
 * <p>Jira's create API offers no idempotency key, so a timed-out {@code POST /issue} leaves us
 * unable to tell whether an issue was made. Retrying blindly risks a duplicate ticket; giving up
 * risks an orphaned issue carrying a placeholder that links nowhere. Both are worse than waiting
 * two minutes and finding out.
 *
 * <p>The search is deliberately plain JQL — project, creator and a creation window — rather than
 * an {@code issue.property} query. On Jira Cloud, entity properties are JQL-searchable only for
 * Forge and Connect apps, which decision D4 rules out
 * (docs/00-verified-capabilities.md 0.9). The window is a few minutes and the creator is the
 * integration identity, so the candidate set is small; properties are then fetched per candidate.
 *
 * <p>The protocol fails to a human, never to a guess.
 */
public final class AmbiguityResolver {

    private final JiraIssueSearch search;
    private final Clock clock;
    private final Duration windowSlack;
    private final int maxSweeps;

    public AmbiguityResolver(JiraIssueSearch search, Clock clock, Duration windowSlack, int maxSweeps) {
        this.search = Objects.requireNonNull(search, "search");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.windowSlack = Objects.requireNonNull(windowSlack, "windowSlack");
        if (maxSweeps < 1) {
            throw new IllegalArgumentException("maxSweeps must be at least 1");
        }
        this.maxSweeps = maxSweeps;
    }

    /** Two minutes of slack either side, three sweeps — the defaults from the design. */
    public static AmbiguityResolver withDefaults(JiraIssueSearch search, Clock clock) {
        return new AmbiguityResolver(search, clock, Duration.ofMinutes(2), 3);
    }

    /**
     * @param sweepsAlreadyPerformed how many times this ticket has been swept before, so the
     *                               caller owns the counter and the resolver stays stateless
     */
    public AmbiguityResolution resolve(AmbiguityContext context, int sweepsAlreadyPerformed) {
        Objects.requireNonNull(context, "context");
        int sweep = sweepsAlreadyPerformed + 1;

        Instant from = context.attemptStartedAt().minus(windowSlack);
        Instant to = clock.instant().plus(windowSlack);

        List<IssueCandidate> candidates =
                search.findCreatedBy(context.projectKey(), context.identityRef(), from, to);

        if (candidates.isEmpty()) {
            return sweep >= maxSweeps
                    ? new AmbiguityResolution.NotCreated(sweep)
                    : new AmbiguityResolution.Inconclusive(sweep, "NO_CANDIDATES_YET");
        }

        var correlationMatches = new ArrayList<IssueCandidate>();
        var withoutOrigin = new ArrayList<IssueCandidate>();

        for (IssueCandidate candidate : candidates) {
            Optional<JiraIssueSearch.JvaultOrigin> origin = search.readOrigin(candidate.issueId());
            if (origin.isEmpty()) {
                // The create landed but the follow-up property write did not — or this issue
                // simply is not ours. Only the heuristic can tell them apart.
                withoutOrigin.add(candidate);
            } else if (Objects.equals(origin.get().correlationId(), context.correlationId())) {
                correlationMatches.add(candidate);
            }
            // An origin with a different correlationId is conclusively somebody else's ticket,
            // so it is excluded from the heuristic pool entirely.
        }

        if (correlationMatches.size() == 1) {
            IssueCandidate match = correlationMatches.get(0);
            return new AmbiguityResolution.Adopted(match.issueId(), match.issueKey(),
                    AmbiguityResolution.MatchBasis.CORRELATION_PROPERTY);
        }
        if (correlationMatches.size() > 1) {
            // Two issues carrying our correlation id means a duplicate already exists. Merging
            // is a judgement call about live tickets, so it is not one to automate.
            return new AmbiguityResolution.NeedsOperator(correlationMatches,
                    "MULTIPLE_CORRELATION_MATCHES");
        }

        return resolveByHeuristic(context, withoutOrigin, sweep);
    }

    private AmbiguityResolution resolveByHeuristic(AmbiguityContext context,
                                                   List<IssueCandidate> withoutOrigin,
                                                   int sweep) {
        if (withoutOrigin.isEmpty()) {
            // Every candidate belongs to a different correlation id, so ours was never created.
            return sweep >= maxSweeps
                    ? new AmbiguityResolution.NotCreated(sweep)
                    : new AmbiguityResolution.Inconclusive(sweep, "ONLY_FOREIGN_CANDIDATES");
        }

        if (context.expectedSummary() == null || context.expectedSummary().isBlank()) {
            // Candidates we cannot identify and nothing to compare them against. Retrying could
            // duplicate one of these; abandoning could orphan one. Hand it over.
            return new AmbiguityResolution.NeedsOperator(withoutOrigin, "CANNOT_DISCRIMINATE");
        }

        List<IssueCandidate> summaryMatches = withoutOrigin.stream()
                .filter(c -> context.expectedSummary().equals(c.summary()))
                .toList();

        if (summaryMatches.size() == 1) {
            IssueCandidate match = summaryMatches.get(0);
            return new AmbiguityResolution.Adopted(match.issueId(), match.issueKey(),
                    AmbiguityResolution.MatchBasis.SUMMARY_HEURISTIC);
        }
        if (summaryMatches.size() > 1) {
            return new AmbiguityResolution.NeedsOperator(summaryMatches, "AMBIGUOUS_SUMMARY_MATCH");
        }

        // Candidates exist, none is ours by property or by summary. Most likely other tickets
        // created by the same identity in the same window.
        return sweep >= maxSweeps
                ? new AmbiguityResolution.NotCreated(sweep)
                : new AmbiguityResolution.Inconclusive(sweep, "NO_MATCHING_CANDIDATE");
    }
}
