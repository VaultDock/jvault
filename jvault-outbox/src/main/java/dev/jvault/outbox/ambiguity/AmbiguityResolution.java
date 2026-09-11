package dev.jvault.outbox.ambiguity;

import dev.jvault.jira.gateway.JiraIssueSearch.IssueCandidate;

import java.util.List;

/**
 * What the ambiguity protocol concluded about a creation whose outcome was unknown.
 *
 * <p>Sealed on purpose: every caller must handle all four cases, and in particular must not
 * quietly treat "we could not tell" as "it was not created" — that conflation is precisely how
 * duplicate tickets get made.
 */
public sealed interface AmbiguityResolution {

    /** The issue exists and is ours. Adopt it and continue from the link-attachment step. */
    record Adopted(String issueId, String issueKey, MatchBasis basis) implements AmbiguityResolution {

        /** A heuristic adoption is correct but unproven, so an operator confirms it later. */
        public boolean needsOperatorConfirmation() {
            return basis == MatchBasis.SUMMARY_HEURISTIC;
        }
    }

    /**
     * The creation demonstrably did not take effect. Safe to retry — this is the only
     * resolution that permits one.
     */
    record NotCreated(int sweepsPerformed) implements AmbiguityResolution {
    }

    /** Not yet decidable. Sweep again; Jira indexing lag is the usual cause. */
    record Inconclusive(int sweepsPerformed, String reasonCode) implements AmbiguityResolution {
    }

    /**
     * A human decides. Reached when candidates exist that cannot be told apart — retrying would
     * risk a duplicate and abandoning would risk an orphaned issue, and neither is a call the
     * system should make on its own.
     */
    record NeedsOperator(List<IssueCandidate> candidates, String reasonCode)
            implements AmbiguityResolution {

        public NeedsOperator {
            candidates = List.copyOf(candidates);
        }
    }

    enum MatchBasis {
        /** {@code jvault.origin.correlationId} matched. Conclusive. */
        CORRELATION_PROPERTY,
        /**
         * Summary, creator and creation window all matched, but the property was absent —
         * the create succeeded and the follow-up property write did not. Strong, not conclusive.
         */
        SUMMARY_HEURISTIC
    }
}
