package dev.jvault.outbox.ambiguity;

import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxRepository;
import dev.jvault.outbox.OutboxState;
import dev.jvault.outbox.TicketStateSink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The worker that actually runs the ambiguity protocol.
 *
 * <p>{@link AmbiguityResolver} decides; this finds the work and applies the decision. Without it
 * the protocol is a well-tested function nobody calls, and a ticket whose create timed out would
 * sit {@code IN_FLIGHT} for ever — which is safe, in that it never duplicates, but is not the same
 * as recovered.
 *
 * <p>Only creations are held. Every other operation is idempotent enough that an unknown outcome
 * is simply retried (see {@link JiraOperation#isIdempotent()}), so entries reaching here are
 * expected to be creates, and anything else is released back to the queue rather than being
 * subjected to a protocol designed for a different problem.
 */
public final class AmbiguityReconciler {

    private final OutboxRepository outbox;
    private final AmbiguityResolver resolver;
    private final TicketStateSink ticketStates;
    private final AmbiguityContextSource contexts;
    private final SweepCounter sweeps;
    private final Clock clock;
    private final Duration settleDelay;

    public AmbiguityReconciler(OutboxRepository outbox,
                               AmbiguityResolver resolver,
                               TicketStateSink ticketStates,
                               AmbiguityContextSource contexts,
                               SweepCounter sweeps,
                               Clock clock,
                               Duration settleDelay) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.ticketStates = Objects.requireNonNull(ticketStates, "ticketStates");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.sweeps = Objects.requireNonNull(sweeps, "sweeps");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settleDelay = Objects.requireNonNull(settleDelay, "settleDelay");
    }

    /**
     * One sweep.
     *
     * @param batchSize how many held entries to examine
     */
    public Report sweep(int batchSize) {
        // The settle delay matters: Jira's search index is not updated synchronously with a
        // create, so sweeping the instant a request times out would reliably find nothing and
        // conclude, wrongly, that nothing was created.
        Instant threshold = clock.instant().minus(settleDelay);
        List<OutboxEntry> held = outbox.findInFlightOlderThan(threshold);

        var outcomes = new ArrayList<Outcome>();
        int examined = 0;

        for (OutboxEntry entry : held) {
            if (examined++ >= batchSize) {
                break;
            }
            if (entry.operation() != JiraOperation.CREATE_ISSUE) {
                // Not a create, so its outcome being unknown was never ambiguous in the sense
                // this protocol addresses. Put it back and let the dispatcher retry it.
                outbox.save(entry.withState(OutboxState.PENDING));
                outcomes.add(new Outcome(entry.ticketRef(), "RELEASED_IDEMPOTENT", null));
                continue;
            }
            outcomes.add(resolveOne(entry));
        }
        return new Report(List.copyOf(outcomes));
    }

    private Outcome resolveOne(OutboxEntry entry) {
        TicketStateSink.AmbiguityContext context = contexts.contextFor(entry);
        if (context == null) {
            return new Outcome(entry.ticketRef(), "NO_CONTEXT", null);
        }

        int already = sweeps.sweepsFor(entry.ticketRef());
        AmbiguityResolution resolution;
        try {
            resolution = resolver.resolve(context, already);
        } catch (RuntimeException e) {
            // A failed search means "cannot tell". Leaving the entry held is the safe answer:
            // the alternative is concluding nothing was created, which duplicates a live ticket.
            sweeps.recordSweep(entry.ticketRef());
            return new Outcome(entry.ticketRef(), "SEARCH_FAILED", null);
        }
        sweeps.recordSweep(entry.ticketRef());

        return switch (resolution) {
            case AmbiguityResolution.Adopted adopted -> {
                // The issue exists and is ours. Close out the create and let everything else for
                // this ticket follow it onto the issue's lane.
                outbox.save(entry.succeeded());
                outbox.moveToLane(entry.ticketRef(), "issue:" + adopted.issueId());
                ticketStates.ticketBecameActive(entry.ticketRef(), adopted.issueId(),
                        adopted.issueKey(), clock.instant());
                yield new Outcome(entry.ticketRef(),
                        adopted.needsOperatorConfirmation()
                                ? "ADOPTED_HEURISTICALLY"
                                : "ADOPTED",
                        adopted.issueKey());
            }

            case AmbiguityResolution.NotCreated notCreated -> {
                // Proven absent, so retrying is safe — and this is the only resolution that
                // permits it.
                outbox.save(entry.withState(OutboxState.PENDING));
                sweeps.clear(entry.ticketRef());
                yield new Outcome(entry.ticketRef(), "RETRY_CREATE", null);
            }

            case AmbiguityResolution.Inconclusive inconclusive ->
                // Held. Jira indexing lag is the usual cause and another sweep usually settles it.
                    new Outcome(entry.ticketRef(),
                            "INCONCLUSIVE:" + inconclusive.reasonCode(), null);

            case AmbiguityResolution.NeedsOperator needsOperator -> {
                ticketStates.ticketFailed(entry.ticketRef(),
                        "AMBIGUOUS_" + needsOperator.reasonCode(), clock.instant());
                yield new Outcome(entry.ticketRef(),
                        "NEEDS_OPERATOR:" + needsOperator.reasonCode(), null);
            }
        };
    }

    /**
     * Recovers the context captured before the uncertain call.
     *
     * <p>A port because the context lives with the ticket, which this module does not know about.
     * The outbox entry's {@code payloadRef} carries enough to rebuild it, and an implementation
     * may prefer the ticket record.
     */
    @FunctionalInterface
    public interface AmbiguityContextSource {
        TicketStateSink.AmbiguityContext contextFor(OutboxEntry entry);

        /** Rebuilds the context from what the create effect recorded. */
        static AmbiguityContextSource fromPayloadRef() {
            return entry -> {
                Map<String, String> ref = entry.payloadRef();
                if (ref.get("projectKey") == null || entry.attemptStartedAt() == null) {
                    return null;
                }
                return new TicketStateSink.AmbiguityContext(
                        entry.ticketRef(), entry.deploymentId(), ref.get("projectKey"),
                        entry.identityRef(), ref.get("correlationId"),
                        ref.get("expectedSummary"), entry.attemptStartedAt());
            };
        }
    }

    /**
     * Counts sweeps per ticket, because the resolver is stateless and the budget has to live
     * somewhere durable — an in-memory counter would reset on deploy and sweep for ever.
     */
    public interface SweepCounter {
        int sweepsFor(String ticketRef);

        void recordSweep(String ticketRef);

        void clear(String ticketRef);
    }

    /** @param detail the issue key when one was adopted */
    public record Outcome(String ticketRef, String disposition, String detail) {
    }

    public record Report(List<Outcome> outcomes) {

        public long count(String dispositionPrefix) {
            return outcomes.stream()
                    .filter(o -> o.disposition().startsWith(dispositionPrefix))
                    .count();
        }

        public boolean isEmpty() {
            return outcomes.isEmpty();
        }
    }
}
