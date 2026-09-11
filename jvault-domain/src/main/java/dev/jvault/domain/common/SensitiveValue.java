package dev.jvault.domain.common;

import java.util.Objects;

/**
 * Carrier for content that must never reach Jira, logs, telemetry or error responses.
 *
 * <p>This is the type-level half of the leak boundary (docs/03-architecture.md 3.3.3). The
 * value is reachable only through {@link #reveal()}, which is deliberately awkward to call
 * and easy to grep for. Everything that stringifies a value by accident — string
 * concatenation, {@code String.valueOf}, logging frameworks, exception messages, JSON
 * serialisers that fall back to {@code toString} — gets the redaction marker instead.
 *
 * <p>It is not a security control on its own; it is the thing that makes the common mistake
 * loud. The hash check in the egress guard and the canary test suite are what actually
 * enforce the boundary.
 */
public final class SensitiveValue {

    private static final String REDACTED = "«redacted»";

    private final String value;
    private final String label;

    private SensitiveValue(String value, String label) {
        this.value = Objects.requireNonNull(value, "value");
        this.label = Objects.requireNonNull(label, "label");
    }

    /**
     * @param label a non-sensitive identifier (a part id, a field key) safe to appear in the
     *              redaction marker, so a redacted log line still says <em>which</em> value
     *              was withheld.
     */
    public static SensitiveValue of(String value, String label) {
        return new SensitiveValue(value, label);
    }

    /** Unwraps the value. Every call site is a place the boundary could be crossed. */
    public String reveal() {
        return value;
    }

    public String label() {
        return label;
    }

    public int length() {
        return value.length();
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    @Override
    public String toString() {
        return REDACTED + ":" + label;
    }

    /**
     * Constant-time-ish equality on the underlying value. Two sensitive values are equal when
     * their content is equal, regardless of label — labels are for humans, not identity.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof SensitiveValue other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
