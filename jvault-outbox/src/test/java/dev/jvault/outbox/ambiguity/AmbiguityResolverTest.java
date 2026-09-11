package dev.jvault.outbox.ambiguity;

import dev.jvault.outbox.TicketStateSink.AmbiguityContext;
import dev.jvault.outbox.support.FakeJiraIssueSearch;
import dev.jvault.outbox.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/12-reliability.md 12.4.2. The governing principle of every test here: the protocol may
 * conclude "yes", "no", or "ask a human" — but it must never guess, because a guess either
 * duplicates a live ticket or orphans one.
 */
class AmbiguityResolverTest {

    private static final String TICKET = "01J8ZQK5M3T4X9YV2A0B7CDEFG";
    private static final String CORRELATION = "corr-8f21c";
    private static final String SUMMARY = "[HIGH] Unexpected outbound connection from build agent";
    private static final Instant ATTEMPT_STARTED = Instant.parse("2026-09-11T09:41:12Z");

    private final TestClock clock = TestClock.at("2026-09-11T09:41:40Z");
    private final FakeJiraIssueSearch search = new FakeJiraIssueSearch();
    private final AmbiguityResolver resolver = AmbiguityResolver.withDefaults(search, clock);

    @Nested
    @DisplayName("conclusive adoption")
    class Adoption {

        @Test
        @DisplayName("a matching correlation property adopts the issue")
        void correlationPropertyMatch() {
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED.plusSeconds(1))
                    .withOrigin("10001", TICKET, CORRELATION);

            AmbiguityResolution resolution = resolver.resolve(context(SUMMARY), 0);

            assertThat(resolution).isInstanceOfSatisfying(AmbiguityResolution.Adopted.class, a -> {
                assertThat(a.issueKey()).isEqualTo("SEC-4471");
                assertThat(a.basis()).isEqualTo(AmbiguityResolution.MatchBasis.CORRELATION_PROPERTY);
                assertThat(a.needsOperatorConfirmation()).isFalse();
            });
        }

        @Test
        @DisplayName("our issue is found among other tickets created by the same identity")
        void findsOursAmongOthers() {
            search.withIssue("10001", "SEC-4470", "Another incident", ATTEMPT_STARTED)
                    .withOrigin("10001", "other-ticket", "corr-other")
                    .withIssue("10002", "SEC-4471", SUMMARY, ATTEMPT_STARTED.plusSeconds(1))
                    .withOrigin("10002", TICKET, CORRELATION)
                    .withIssue("10003", "SEC-4472", "Yet another", ATTEMPT_STARTED.plusSeconds(2))
                    .withOrigin("10003", "third-ticket", "corr-third");

            assertThat(resolver.resolve(context(SUMMARY), 0))
                    .isInstanceOfSatisfying(AmbiguityResolution.Adopted.class,
                            a -> assertThat(a.issueKey()).isEqualTo("SEC-4471"));
        }

        @Test
        @DisplayName("when the property write also failed, the summary heuristic adopts — but flags it")
        void summaryHeuristicWhenPropertyMissing() {
            // The create landed; the follow-up property write did not.
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED.plusSeconds(1));

            AmbiguityResolution resolution = resolver.resolve(context(SUMMARY), 0);

            assertThat(resolution).isInstanceOfSatisfying(AmbiguityResolution.Adopted.class, a -> {
                assertThat(a.issueKey()).isEqualTo("SEC-4471");
                assertThat(a.basis()).isEqualTo(AmbiguityResolution.MatchBasis.SUMMARY_HEURISTIC);
                assertThat(a.needsOperatorConfirmation())
                        .as("strong evidence, but not proof — a human confirms later")
                        .isTrue();
            });
        }
    }

    @Nested
    @DisplayName("concluding the issue was never created")
    class NotCreated {

        @Test
        @DisplayName("no candidates yet is inconclusive, not a licence to retry")
        void noCandidatesIsInconclusiveAtFirst() {
            AmbiguityResolution resolution = resolver.resolve(context(SUMMARY), 0);

            assertThat(resolution)
                    .isInstanceOfSatisfying(AmbiguityResolution.Inconclusive.class,
                            i -> assertThat(i.reasonCode()).isEqualTo("NO_CANDIDATES_YET"));
        }

        @Test
        @DisplayName("after the sweep budget, an empty result means it was never created")
        void emptyAfterMaxSweepsMeansNotCreated() {
            assertThat(resolver.resolve(context(SUMMARY), 2))
                    .isInstanceOfSatisfying(AmbiguityResolution.NotCreated.class,
                            n -> assertThat(n.sweepsPerformed()).isEqualTo(3));
        }

        @Test
        @DisplayName("candidates that all belong to other tickets mean ours was never created")
        void onlyForeignCandidates() {
            search.withIssue("10001", "SEC-4470", "Another incident", ATTEMPT_STARTED)
                    .withOrigin("10001", "other-ticket", "corr-other");

            assertThat(resolver.resolve(context(SUMMARY), 0))
                    .isInstanceOfSatisfying(AmbiguityResolution.Inconclusive.class,
                            i -> assertThat(i.reasonCode()).isEqualTo("ONLY_FOREIGN_CANDIDATES"));
            assertThat(resolver.resolve(context(SUMMARY), 2))
                    .isInstanceOf(AmbiguityResolution.NotCreated.class);
        }

        @Test
        @DisplayName("an unidentified candidate with a different summary is not ours")
        void unidentifiedCandidateWithDifferentSummary() {
            search.withIssue("10001", "SEC-4470", "A completely different summary", ATTEMPT_STARTED);

            assertThat(resolver.resolve(context(SUMMARY), 2))
                    .isInstanceOf(AmbiguityResolution.NotCreated.class);
        }
    }

    @Nested
    @DisplayName("handing over to a human")
    class NeedsOperator {

        @Test
        @DisplayName("two issues carrying our correlation id means a duplicate already exists")
        void multipleCorrelationMatches() {
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED)
                    .withOrigin("10001", TICKET, CORRELATION)
                    .withIssue("10002", "SEC-4472", SUMMARY, ATTEMPT_STARTED.plusSeconds(1))
                    .withOrigin("10002", TICKET, CORRELATION);

            assertThat(resolver.resolve(context(SUMMARY), 0))
                    .isInstanceOfSatisfying(AmbiguityResolution.NeedsOperator.class, n -> {
                        assertThat(n.reasonCode()).isEqualTo("MULTIPLE_CORRELATION_MATCHES");
                        assertThat(n.candidates()).hasSize(2);
                    });
        }

        @Test
        @DisplayName("two unidentified issues with the same summary cannot be told apart")
        void ambiguousSummaryMatch() {
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED)
                    .withIssue("10002", "SEC-4472", SUMMARY, ATTEMPT_STARTED.plusSeconds(1));

            assertThat(resolver.resolve(context(SUMMARY), 0))
                    .isInstanceOfSatisfying(AmbiguityResolution.NeedsOperator.class, n -> {
                        assertThat(n.reasonCode()).isEqualTo("AMBIGUOUS_SUMMARY_MATCH");
                        assertThat(n.candidates()).hasSize(2);
                    });
        }

        @Test
        @DisplayName("with nothing to compare against, an unidentified candidate goes to a human")
        void cannotDiscriminateWithoutASummary() {
            search.withIssue("10001", "SEC-4471", "Whatever this is", ATTEMPT_STARTED);

            // Retrying could duplicate this issue; abandoning could orphan it.
            assertThat(resolver.resolve(context(null), 0))
                    .isInstanceOfSatisfying(AmbiguityResolution.NeedsOperator.class,
                            n -> assertThat(n.reasonCode()).isEqualTo("CANNOT_DISCRIMINATE"));
        }
    }

    @Nested
    @DisplayName("safety properties")
    class Safety {

        @Test
        @DisplayName("an issue proven to belong to another ticket is never adopted, summary or not")
        void foreignIssueIsNeverAdoptedEvenWithMatchingSummary() {
            // Same summary — two alerts of the same kind — but a conclusive, different origin.
            search.withIssue("10001", "SEC-4470", SUMMARY, ATTEMPT_STARTED)
                    .withOrigin("10001", "a-different-ticket", "corr-different");

            AmbiguityResolution resolution = resolver.resolve(context(SUMMARY), 0);

            assertThat(resolution)
                    .as("a known origin is conclusive; the summary heuristic must not override it")
                    .isNotInstanceOf(AmbiguityResolution.Adopted.class);
            assertThat(resolution).isInstanceOf(AmbiguityResolution.Inconclusive.class);
        }

        @Test
        @DisplayName("the search window brackets the attempt on both sides")
        void searchWindowIncludesSlackEitherSide() {
            // Created 90 s before the attempt started — clock skew between jvault and Jira.
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED.minusSeconds(90))
                    .withOrigin("10001", TICKET, CORRELATION);

            assertThat(resolver.resolve(context(SUMMARY), 0))
                    .isInstanceOf(AmbiguityResolution.Adopted.class);
        }

        @Test
        @DisplayName("an issue outside the window is not considered")
        void outsideWindowIsIgnored() {
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED.minus(Duration.ofMinutes(30)))
                    .withOrigin("10001", TICKET, CORRELATION);

            assertThat(resolver.resolve(context(SUMMARY), 2))
                    .isInstanceOf(AmbiguityResolution.NotCreated.class);
        }

        @Test
        @DisplayName("the resolver is stateless — the caller owns the sweep counter")
        void resolverIsStateless() {
            search.withIssue("10001", "SEC-4471", SUMMARY, ATTEMPT_STARTED)
                    .withOrigin("10001", TICKET, CORRELATION);

            assertThat(resolver.resolve(context(SUMMARY), 0))
                    .isEqualTo(resolver.resolve(context(SUMMARY), 0));
        }

        @Test
        @DisplayName("the candidate search is bounded, not a full-project scan")
        void searchIsBounded() {
            resolver.resolve(context(SUMMARY), 0);

            // One narrow query per sweep: project + creator + a few-minute window.
            assertThat(search.searchCalls()).isEqualTo(1);
        }
    }

    private AmbiguityContext context(String expectedSummary) {
        return new AmbiguityContext(TICKET, "jira-cloud-prod", "SEC",
                "INTEGRATION:svc-jvault", CORRELATION, expectedSummary, ATTEMPT_STARTED);
    }
}
