package dev.jvault.ingest.processing;

import dev.jvault.ingest.mapping.EventMapper.FieldProblem;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What goes on the dead-letter topic when a message cannot be processed.
 *
 * <p><strong>There is no payload field, and that is the design.</strong> The obvious
 * implementation — republish the failed message so somebody can inspect it — copies source data
 * onto a new topic with different retention and different access control, which is exactly the
 * leak this system exists to prevent. A security alert that failed validation may well carry the
 * credential that caused the alert (docs/05-content-placement.md 5.6).
 *
 * <p>Instead the record carries coordinates, a failure classification, field-level problems by
 * <em>code</em>, and a reference into the encrypted quarantine. Replaying reads from quarantine,
 * not from this topic, and every such read is audited.
 *
 * @param quarantineRef where the original is held, encrypted, under a restricted ACL and its own
 *                      short retention. Null only when quarantining itself failed
 */
public record DeadLetterRecord(String correlationId,
                               Source source,
                               String mappingId,
                               Failure failure,
                               List<FieldProblem> fieldProblems,
                               String quarantineRef,
                               Instant occurredAt) {

    public DeadLetterRecord {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(failure, "failure");
        fieldProblems = fieldProblems == null ? List.of() : List.copyOf(fieldProblems);
    }

    public record Source(String topic, int partition, long offset) {
    }

    /**
     * @param stage   where processing stopped, so an operator knows whether Jira was ever touched
     * @param code    a stable classification, safe to alert on and to use as a metric label
     * @param attempts how many times this was tried before giving up
     */
    public record Failure(String stage, String code, int attempts) {
    }

    /**
     * Identifiers and codes only — asserted by test, because this type's whole purpose is to be
     * the one place a payload could plausibly have been included and was not.
     */
    @Override
    public String toString() {
        return "DeadLetterRecord[" + correlationId + ", " + source.topic() + "/"
                + source.partition() + "/" + source.offset() + ", " + failure.code()
                + ", problems=" + fieldProblems + "]";
    }
}
