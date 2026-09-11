package dev.jvault.persistence.dialect;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.persistence.OutboxRowMapper;
import dev.jvault.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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
                OUTPUT """ + OutboxRowMapper.outputColumns("inserted");
    }

    @Override
    public String id() {
        return "sqlserver";
    }

    @Override
    public List<OutboxEntry> claimDue(JdbcTemplate jdbc, int limit, Instant now) {
        return jdbc.query(claimSql, OutboxRowMapper.rowMapper(), limit, Timestamp.from(now));
    }

    @Override
    public boolean verifiedByIntegrationTests() {
        return false;
    }
}
