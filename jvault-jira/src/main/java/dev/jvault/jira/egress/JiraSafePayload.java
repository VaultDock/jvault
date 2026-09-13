package dev.jvault.jira.egress;

import dev.jvault.jira.egress.JiraFieldEncoding;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Jira write that has passed the egress guard.
 *
 * <p>The constructor is package-private and {@link EgressGuard} is the only class in this
 * package that calls it, so a {@code JiraSafePayload} cannot exist without having been checked.
 * {@link dev.jvault.jira.gateway.JiraWriteGateway} accepts nothing else, which is what makes
 * "every Jira write is sanitised" a property of the type system rather than of code review
 * (docs/03-architecture.md 3.3.3).
 *
 * <p>Do not add a public constructor, a public factory, a copy constructor, a builder, or a
 * deserialisation path. Each of those is a hole in the boundary. The ArchUnit rules in
 * {@code EgressBoundaryArchTest} exist to catch it if someone does.
 */
public final class JiraSafePayload {

    private final JiraOperation operation;
    private final String ticketRef;
    private final String issueLane;
    private final Map<String, String> textFields;
    private final Map<String, JiraFieldEncoding> encodings;
    private final Map<String, String> properties;
    private final List<String> checksPerformed;

    JiraSafePayload(JiraOperation operation,
                    String ticketRef,
                    String issueLane,
                    Map<String, String> textFields,
                    Map<String, JiraFieldEncoding> encodings,
                    Map<String, String> properties,
                    List<String> checksPerformed) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.ticketRef = Objects.requireNonNull(ticketRef, "ticketRef");
        this.issueLane = Objects.requireNonNull(issueLane, "issueLane");
        this.textFields = Map.copyOf(textFields);
        this.encodings = Map.copyOf(encodings);
        this.properties = Map.copyOf(properties);
        this.checksPerformed = List.copyOf(checksPerformed);
    }

    /** How each field's value becomes JSON. Absent means a plain string. */
    public JiraFieldEncoding encodingOf(String fieldKey) {
        return encodings.getOrDefault(fieldKey, JiraFieldEncoding.STRING);
    }

    /**
     * Issue properties to write with this operation, as property key to JSON text.
     *
     * <p>Checked by the guard exactly like a field value, because they are outbound strings and
     * a bug that put content in a property would be no less a leak for being in a property.
     */
    public Map<String, String> properties() {
        return properties;
    }

    public JiraOperation operation() {
        return operation;
    }

    public String ticketRef() {
        return ticketRef;
    }

    public String issueLane() {
        return issueLane;
    }

    public Map<String, String> textFields() {
        return textFields;
    }

    /** Which checks ran, recorded so the audit trail can prove the payload was examined. */
    public List<String> checksPerformed() {
        return checksPerformed;
    }

    /**
     * Identifiers only. A payload's field <em>values</em> are Jira-bound and therefore
     * non-sensitive by construction, but printing them here would make every log line that
     * touches a payload a place where that assumption has to hold. It is cheaper to never
     * print them.
     */
    @Override
    public String toString() {
        return "JiraSafePayload[" + operation + ", ticket=" + ticketRef
                + ", lane=" + issueLane + ", fields=" + textFields.keySet() + "]";
    }
}
