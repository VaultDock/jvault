package dev.jvault.jira.egress;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A <em>candidate</em> Jira write, before it has been checked.
 *
 * <p>This is the untrusted side of the boundary. It is what the outbox dispatcher assembles by
 * re-reading values from the database at execution time, and the only way to turn it into
 * something {@link dev.jvault.jira.gateway.JiraWriteGateway} will accept is to pass it through
 * {@link EgressGuard}.
 *
 * @param operation      what is being done to Jira
 * @param ticketRef      jvault's ticket identifier, for audit correlation
 * @param issueLane      the serialisation lane: the Jira issue id, or {@code ticket:<ref>}
 *                       before the issue exists (docs/04-data-model.md 4.3)
 * @param classification sensitivity of the ticket, deciding whether the classifier blocks
 * @param textFields     Jira-bound text, keyed by Jira field key. These are surrogates and
 *                       JIRA-placed values — never the value of an externally-placed part
 * @param externalValues values of this ticket's externally-placed parts. Supplied so the guard
 *                       can prove none of them appear in {@code textFields}; they are never
 *                       sent anywhere
 */
public record JiraWriteRequest(
        JiraOperation operation,
        String ticketRef,
        String issueLane,
        Classification classification,
        Map<String, String> textFields,
        List<SensitiveValue> externalValues) {

    public JiraWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(ticketRef, "ticketRef");
        Objects.requireNonNull(issueLane, "issueLane");
        Objects.requireNonNull(classification, "classification");
        textFields = textFields == null ? Map.of() : Map.copyOf(textFields);
        externalValues = externalValues == null ? List.of() : List.copyOf(externalValues);
    }

    public static Builder builder(JiraOperation operation, String ticketRef) {
        return new Builder(operation, ticketRef);
    }

    public static final class Builder {
        private final JiraOperation operation;
        private final String ticketRef;
        private String issueLane;
        private Classification classification = Classification.INTERNAL;
        private final Map<String, String> textFields = new LinkedHashMap<>();
        private final List<SensitiveValue> externalValues = new java.util.ArrayList<>();

        private Builder(JiraOperation operation, String ticketRef) {
            this.operation = operation;
            this.ticketRef = ticketRef;
            this.issueLane = "ticket:" + ticketRef;
        }

        public Builder issueLane(String v) { this.issueLane = v; return this; }
        public Builder classification(Classification v) { this.classification = v; return this; }
        public Builder field(String key, String value) { this.textFields.put(key, value); return this; }
        public Builder externalValue(SensitiveValue v) { this.externalValues.add(v); return this; }
        public Builder externalValues(List<SensitiveValue> v) { this.externalValues.addAll(v); return this; }

        public JiraWriteRequest build() {
            return new JiraWriteRequest(operation, ticketRef, issueLane, classification,
                    textFields, externalValues);
        }
    }
}
