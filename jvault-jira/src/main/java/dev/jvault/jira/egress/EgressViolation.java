package dev.jvault.jira.egress;

import java.util.Objects;

/**
 * One reason a Jira write was refused.
 *
 * <p>Carries identifiers and codes only. Rendering a violation into a log line, an audit record
 * or an API error must never require access to the content that caused it — that constraint is
 * why this type has no field capable of holding one.
 *
 * @param fieldKey  which Jira-bound field failed, e.g. {@code description}
 * @param code      stable violation code, safe to expose and to alert on
 * @param detail    non-sensitive elaboration: a match kind, a detector code, an offset
 */
public record EgressViolation(String fieldKey, String code, String detail) {

    public static final String EXTERNAL_CONTENT_IN_PAYLOAD = "EXTERNAL_CONTENT_IN_PAYLOAD";
    public static final String CLASSIFIED_CONTENT_DETECTED = "CLASSIFIED_CONTENT_DETECTED";
    public static final String SENSITIVE_VALUE_STRINGIFIED = "SENSITIVE_VALUE_STRINGIFIED";

    public EgressViolation {
        Objects.requireNonNull(fieldKey, "fieldKey");
        Objects.requireNonNull(code, "code");
        detail = detail == null ? "" : detail;
    }

    @Override
    public String toString() {
        return code + "[field=" + fieldKey + (detail.isEmpty() ? "" : ", " + detail) + "]";
    }
}
