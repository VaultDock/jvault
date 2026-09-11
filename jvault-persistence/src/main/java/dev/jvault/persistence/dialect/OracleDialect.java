package dev.jvault.persistence.dialect;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.persistence.OutboxRowMapper;
import dev.jvault.persistence.SqlDialect;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 * bounds that, and the ids are then marked in a second statement inside the same transaction —
 * which is why this dialect drops to a {@link ConnectionCallback} rather than using a plain
 * query: it needs statement-level control that a one-line template call cannot give.
 *
 * <p>This is the least obvious of the three implementations and the easiest to "simplify" into
 * something that compiles, runs, and quietly locks the whole table.
 *
 * <p><strong>Unverified.</strong> Not exercised against a real Oracle in this repository. Treat
 * the SQL as reviewed but unproven (decision D5).
 */
public final class OracleDialect implements SqlDialect {

    private static final String SELECT_DUE = """
            SELECT id FROM jira_outbox
            WHERE state IN ('PENDING', 'FAILED')
              AND next_attempt_at <= ?
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED""";

    private static final String MARK_CLAIMED =
            "UPDATE jira_outbox SET state = 'CLAIMED' WHERE id = ?";

    private final String selectClaimed;

    public OracleDialect() {
        this.selectClaimed = "SELECT " + OutboxRowMapper.columns() + " FROM jira_outbox WHERE id = ?";
    }

    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public List<OutboxEntry> claimDue(JdbcTemplate jdbc, int limit, Instant now) {
        return jdbc.execute((ConnectionCallback<List<OutboxEntry>>) connection -> {
            List<String> ids = new ArrayList<>(limit);

            try (PreparedStatement select = connection.prepareStatement(SELECT_DUE)) {
                // The row limit lives here, not in the SQL. See the class javadoc: combining
                // FOR UPDATE SKIP LOCKED with FETCH FIRST raises ORA-02014, and Oracle locks
                // rows as they are fetched, so bounding the fetch bounds the locking.
                select.setMaxRows(limit);
                select.setFetchSize(limit);
                select.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next() && ids.size() < limit) {
                        ids.add(rs.getString("id"));
                    }
                }
            }
            if (ids.isEmpty()) {
                return List.of();
            }

            try (PreparedStatement update = connection.prepareStatement(MARK_CLAIMED)) {
                for (String id : ids) {
                    update.setString(1, id);
                    update.addBatch();
                }
                update.executeBatch();
            }

            var entries = new ArrayList<OutboxEntry>(ids.size());
            try (PreparedStatement read = connection.prepareStatement(selectClaimed)) {
                for (String id : ids) {
                    read.setString(1, id);
                    try (ResultSet rs = read.executeQuery()) {
                        if (rs.next()) {
                            entries.add(OutboxRowMapper.map(rs));
                        }
                    }
                }
            }
            return entries;
        });
    }

    @Override
    public boolean verifiedByIntegrationTests() {
        return false;
    }
}
