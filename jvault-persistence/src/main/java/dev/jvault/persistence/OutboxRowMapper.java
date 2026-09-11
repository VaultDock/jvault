package dev.jvault.persistence;

import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps outbox rows, identically on every engine.
 *
 * <p>Two portability choices are visible here and both are deliberate (decision D5):
 *
 * <ul>
 *   <li>Identifiers are {@code CHAR(36)} strings rather than a native UUID type. Oracle has no
 *       UUID type and SQL Server's {@code uniqueidentifier} sorts in an order unlike everyone
 *       else's, which would quietly change index behaviour between deployments.</li>
 *   <li>Timestamps are read and written as UTC {@link Instant} against a plain timestamp column.
 *       SQL Server's {@code datetime2} has no time zone at all, and depending on three vendors'
 *       server time zone settings to agree is a bug waiting for a clock change.</li>
 * </ul>
 */
public final class OutboxRowMapper {

    private static final String COLUMNS = """
            id, ticket_ref, deployment_id, issue_lane, operation, effect_key, payload_ref,
            identity_ref, state, attempts, next_attempt_at, last_error_code,
            attempt_started_at, created_at""";

    private OutboxRowMapper() {
    }

    /** The column list, in the order {@link #map} reads it. */
    public static String columns() {
        return COLUMNS.replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * The same columns qualified for SQL Server's {@code OUTPUT inserted.*} clause.
     *
     * <p>Written out rather than using {@code inserted.*} so that a column added to the table
     * without being added to the mapper is a compile-time-visible omission rather than a silent
     * mismatch at runtime.
     */
    public static String outputColumns(String prefix) {
        var qualified = new StringBuilder();
        for (String column : columns().split(",")) {
            if (!qualified.isEmpty()) {
                qualified.append(", ");
            }
            qualified.append(prefix).append('.').append(column.trim());
        }
        return qualified.toString();
    }

    public static OutboxEntry map(ResultSet rs) throws SQLException {
        return new OutboxEntry(
                UUID.fromString(rs.getString("id").trim()),
                rs.getString("ticket_ref"),
                rs.getString("deployment_id"),
                rs.getString("issue_lane"),
                JiraOperation.valueOf(rs.getString("operation")),
                rs.getString("effect_key"),
                decodePayloadRef(rs.getString("payload_ref")),
                rs.getString("identity_ref"),
                OutboxState.valueOf(rs.getString("state")),
                rs.getInt("attempts"),
                instant(rs.getTimestamp("next_attempt_at")),
                rs.getString("last_error_code"),
                instant(rs.getTimestamp("attempt_started_at")),
                instant(rs.getTimestamp("created_at")));
    }

    public static List<OutboxEntry> mapAll(ResultSet rs) throws SQLException {
        var entries = new ArrayList<OutboxEntry>();
        while (rs.next()) {
            entries.add(map(rs));
        }
        return entries;
    }

    public static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * {@code payloadRef} is a flat map of identifiers, stored as text.
     *
     * <p>Not {@code jsonb}: that is PostgreSQL-only, and the portable alternative costs us
     * SQL-side JSON querying which the outbox never needed. Encoding is deliberately trivial and
     * hand-written rather than pulling in a JSON library for a string-to-string map — the values
     * are identifiers, never content, which is what makes such a simple encoding safe.
     */
    public static String encodePayloadRef(Map<String, String> payloadRef) {
        var sb = new StringBuilder();
        payloadRef.forEach((key, value) -> {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(escape(key)).append('=').append(escape(value));
        });
        return sb.toString();
    }

    public static Map<String, String> decodePayloadRef(String encoded) {
        var map = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isBlank()) {
            return map;
        }
        for (String line : encoded.split("\n", -1)) {
            int at = line.indexOf('=');
            if (at > 0) {
                map.put(unescape(line.substring(0, at)), unescape(line.substring(at + 1)));
            }
        }
        return map;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e");
    }

    private static String unescape(String s) {
        return s.replace("\\e", "=").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
