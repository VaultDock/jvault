package dev.jvault.persistence.dialect;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static dev.jvault.persistence.OutboxRowMapper.columns;
import static dev.jvault.persistence.OutboxRowMapper.mapAll;
import static dev.jvault.persistence.OutboxRowMapper.timestamp;

/**
 * PostgreSQL. The dialect this repository verifies against a real engine.
 *
 * <p>The claim is a single statement: an inner select takes the lock with
 * {@code FOR UPDATE SKIP LOCKED} and the outer update marks and returns the rows. Note the clause
 * order — in PostgreSQL {@code FOR UPDATE} must follow {@code LIMIT}, and writing it the other
 * way round is a syntax error rather than a subtle bug, which is a small mercy.
 */
public final class PostgresDialect implements SqlDialect {

    /** 23505 is the SQL-standard code for a unique violation, which PostgreSQL reports faithfully. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final String claimSql;

    public PostgresDialect() {
        this.claimSql = """
                UPDATE jira_outbox SET state = 'CLAIMED'
                WHERE id IN (
                    SELECT id FROM jira_outbox
                    WHERE state IN ('PENDING', 'FAILED')
                      AND next_attempt_at <= ?
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING """ + columns();
    }

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public List<OutboxEntry> claimDue(Connection connection, int limit, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(claimSql)) {
            statement.setTimestamp(1, timestamp(now));
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapAll(rs);
            }
        }
    }

    @Override
    public boolean isUniqueViolation(SQLException e) {
        return UNIQUE_VIOLATION.equals(e.getSQLState());
    }

    @Override
    public boolean verifiedByIntegrationTests() {
        return true;
    }
}
