package dev.jvault.persistence.dialect;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.persistence.OutboxRowMapper;
import dev.jvault.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL.
 *
 * <p>The claim is a single statement: an inner select takes the lock with
 * {@code FOR UPDATE SKIP LOCKED} and the outer update marks and returns the rows. Note the clause
 * order — in PostgreSQL {@code FOR UPDATE} must follow {@code LIMIT}, and writing it the other
 * way round is a syntax error rather than a subtle bug, which is a small mercy.
 */
public final class PostgresDialect implements SqlDialect {

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
                RETURNING """ + OutboxRowMapper.columns();
    }

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public List<OutboxEntry> claimDue(JdbcTemplate jdbc, int limit, Instant now) {
        return jdbc.query(claimSql, OutboxRowMapper.rowMapper(), Timestamp.from(now), limit);
    }

    @Override
    public boolean verifiedByIntegrationTests() {
        return true;
    }
}
