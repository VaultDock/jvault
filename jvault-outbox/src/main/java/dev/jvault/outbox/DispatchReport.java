package dev.jvault.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** What one dispatcher pass did. Identifiers and counts only — safe to log wholesale. */
public record DispatchReport(List<EntryOutcome> outcomes) {

    public DispatchReport {
        outcomes = List.copyOf(outcomes);
    }

    public static DispatchReport of(List<EntryOutcome> outcomes) {
        return new DispatchReport(outcomes);
    }

    public static DispatchReport empty() {
        return new DispatchReport(List.of());
    }

    public int count(Disposition disposition) {
        return (int) outcomes.stream().filter(o -> o.disposition() == disposition).count();
    }

    public Map<Disposition, Long> summary() {
        return outcomes.stream().collect(
                Collectors.groupingBy(EntryOutcome::disposition, Collectors.counting()));
    }

    public List<EntryOutcome> with(Disposition disposition) {
        return outcomes.stream().filter(o -> o.disposition() == disposition).toList();
    }

    public record EntryOutcome(String ticketRef,
                               String effectKey,
                               String issueLane,
                               Disposition disposition,
                               String errorCode) {
    }

    public enum Disposition {
        SENT,
        /** The effect was overtaken by events; the desired end state already holds. */
        SKIPPED_NOT_APPLICABLE,
        /** Deferred by the per-issue rate limiter; not an error, just not now. */
        DEFERRED_RATE_LIMIT,
        RESCHEDULED_THROTTLED,
        RESCHEDULED_TRANSIENT,
        ABANDONED_REJECTED,
        ABANDONED_EXHAUSTED,
        /** Refused by the egress guard. A security event, never retried. */
        ABANDONED_EGRESS_VIOLATION,
        /** Outcome unknown; handed to the ambiguity resolver. */
        HELD_AMBIGUOUS
    }

    public static final class Builder {
        private final List<EntryOutcome> outcomes = new ArrayList<>();

        public Builder add(OutboxEntry entry, Disposition disposition, String errorCode) {
            outcomes.add(new EntryOutcome(entry.ticketRef(), entry.effectKey(), entry.issueLane(),
                    disposition, errorCode));
            return this;
        }

        public DispatchReport build() {
            return new DispatchReport(outcomes);
        }
    }
}
