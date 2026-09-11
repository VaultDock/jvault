package dev.jvault.persistence.dialect;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static dev.jvault.persistence.OutboxRowMapper.mapAll;
import static dev.jvault.persistence.OutboxRowMapper.outputColumns;
import static dev.jvault.persistence.OutboxRowMapper.timestamp;

/**
 * Microsoft SQL Server.
 *
 * <p>{@code READPAST} is SQL Server's skip-locked: it passes over rows locked by another
 * transaction instead of waiting on them. It has to be paired with {@code UPDLOCK} — without it
 * the rows are only shared-locked and two workers can still read the same row — and with
 * {@code ROWLOCK} to stop the engine escalating to a page lock and skipping rows it should not.
 * All three hints are load-bearing; dropping any one of them produces a queue that looks fine
 * under light load and double-dispatches under contention.
 *
 * <p>{@code UPDATE TOP (n)} on its own does not respect {@code ORDER BY}, so the ordered limit
 * goes in a CTE and the update targets that. {@code OUTPUT inserted.*} replaces
 * {@code RETURNING}.
 *
 * <p><strong>Unverified.</strong> Not exercised against a real SQL Server in this repository —
 * the image needs a licence acceptance that not every CI environment can give. Treat the SQL as
 * reviewed but unproven until an integration run exists (decision D5).
 */
public final class SqlServerDialect implements SqlDialect {

    /** 2627 is a primary key or unique constraint; 2601 is a unique index. Both mean the same here. */
    private static final Set<Integer> UNIQUE_VIOLATION_CODES = Set.of(2627, 2601);

    private final String claimSql;

    public SqlServerDialect() {
        this.claimSql = """
                WITH due AS (
                    SELECT TOP (?) *
                    FROM jira_outbox WITH (UPDLOCK, READPAST, ROWLOCK)
                    WHERE state IN ('PENDING', 'FAILED')
                      AND next_attempt_at <= ?
                    ORDER BY created_at
                )
                UPDATE due SET state = 'CLAIMED'
                OUTPUT """ + outputColumns("inserted");
    }

    @Override
    public String id() {
        return "sqlserver";
    }

    @Override
    public List<OutboxEntry> claimDue(Connection connection, int limit, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(claimSql)) {
            statement.setInt(1, limit);
            statement.setTimestamp(2, timestamp(now));
            try (ResultSet rs = statement.executeQuery()) {
                return mapAll(rs);
            }
        }
    }

    @Override
    public boolean isUniqueViolation(SQLException e) {
        if (e instanceof java.sql.SQLIntegrityConstraintViolationException) {
            return true;
        }
        // SQL Server reports duplicate keys with SQLState 23000 and a vendor code, so the
        // vendor code is what actually distinguishes a duplicate from other integrity failures.
        return UNIQUE_VIOLATION_CODES.contains(e.getErrorCode());
    }

    @Override
    public boolean verifiedByIntegrationTests() {
        return false;
    }
}
