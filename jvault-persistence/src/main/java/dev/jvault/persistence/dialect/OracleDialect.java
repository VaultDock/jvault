package dev.jvault.persistence.dialect;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static dev.jvault.persistence.OutboxRowMapper.columns;
import static dev.jvault.persistence.OutboxRowMapper.map;
import static dev.jvault.persistence.OutboxRowMapper.timestamp;

/**
 * Oracle.
 *
 * <p>Oracle has {@code FOR UPDATE SKIP LOCKED}, but it cannot be combined with a row-limiting
 * clause: {@code FETCH FIRST n ROWS ONLY} and {@code ROWNUM} are both implemented over an inline
 * view, and locking through one raises <strong>ORA-02014</strong>. Wrapping the query in a
 * sub-select does not help, for the same reason.
 *
 * <p>The limit therefore has to come from the <em>fetch</em> rather than from the SQL. Oracle
 * acquires locks as rows are read, so a {@code FOR UPDATE SKIP LOCKED} query whose result set is
 * only partly consumed locks only the rows actually fetched. {@link PreparedStatement#setMaxRows}
 * bounds that, and the ids are then marked in a second statement inside the same transaction.
 *
 * <p>This is the least obvious of the three implementations and the easiest to "simplify" into
 * something that compiles, runs, and quietly locks the whole table.
 *
 * <p><strong>Unverified.</strong> Not exercised against a real Oracle in this repository. Treat
 * the SQL as reviewed but unproven (decision D5).
 */
public final class OracleDialect implements SqlDialect {

    /** ORA-00001: unique constraint violated. */
    private static final int UNIQUE_VIOLATION = 1;

    private static final String SELECT_DUE = """
            SELECT id FROM jira_outbox
            WHERE state IN ('PENDING', 'FAILED')
              AND next_attempt_at <= ?
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED""";

    private final String selectClaimed;

    public OracleDialect() {
        this.selectClaimed = "SELECT " + columns() + " FROM jira_outbox WHERE id = ?";
    }

    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public List<OutboxEntry> claimDue(Connection connection, int limit, Instant now)
            throws SQLException {
        List<String> ids = lockDueIds(connection, limit, now);
        if (ids.isEmpty()) {
            return List.of();
        }
        markClaimed(connection, ids);
        return readBack(connection, ids);
    }

    private List<String> lockDueIds(Connection connection, int limit, Instant now)
            throws SQLException {
        var ids = new ArrayList<String>(limit);
        try (PreparedStatement statement = connection.prepareStatement(SELECT_DUE)) {
            // The row limit lives here, not in the SQL. See the class javadoc: combining
            // FOR UPDATE SKIP LOCKED with FETCH FIRST raises ORA-02014, and Oracle locks rows
            // as they are fetched, so bounding the fetch bounds the locking.
            statement.setMaxRows(limit);
            statement.setFetchSize(limit);
            statement.setTimestamp(1, timestamp(now));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next() && ids.size() < limit) {
                    ids.add(rs.getString("id"));
                }
            }
        }
        return ids;
    }

    private void markClaimed(Connection connection, List<String> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE jira_outbox SET state = 'CLAIMED' WHERE id = ?")) {
            for (String id : ids) {
                statement.setString(1, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<OutboxEntry> readBack(Connection connection, List<String> ids) throws SQLException {
        var entries = new ArrayList<OutboxEntry>(ids.size());
        try (PreparedStatement statement = connection.prepareStatement(selectClaimed)) {
            for (String id : ids) {
                statement.setString(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        entries.add(map(rs));
                    }
                }
            }
        }
        return entries;
    }

    @Override
    public boolean isUniqueViolation(SQLException e) {
        return e.getErrorCode() == UNIQUE_VIOLATION
                || e instanceof java.sql.SQLIntegrityConstraintViolationException;
    }

    @Override
    public boolean verifiedByIntegrationTests() {
        return false;
    }
}
