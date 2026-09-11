package dev.jvault.persistence;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The outbox on Spring JDBC, across all three supported engines.
 *
 * <p>Spring JDBC rather than jOOQ: jOOQ's Open Source Edition is licensed for open-source
 * databases only, and decision D5 puts Oracle and SQL Server in scope. Beyond the licence, the
 * choice suits this class — Spring JDBC does not attempt to generate the dequeue SQL, which is
 * exactly the part that must stay readable, while its exception translation removes the
 * per-dialect duplicate-key detection that would otherwise be hand-written three times.
 *
 * <p>Two contract properties from {@link OutboxRepository} are provided here rather than assumed,
 * because the whole dispatcher rests on them:
 *
 * <ul>
 *   <li><strong>{@code append} is idempotent</strong> on {@code (ticket_ref, effect_key)}. The
 *       unique constraint is the mechanism and the integrity violation is the signal — not a
 *       read-then-write check, which would race between two nodes enqueuing the same effect.</li>
 *   <li><strong>{@code claim} never hands one row to two workers.</strong> Delegated to
 *       {@link SqlDialect#claimDue}, because the skip-locked semantics differ per engine and are
 *       the one thing that must not be abstracted away.</li>
 * </ul>
 */
public final class JdbcOutboxRepository implements OutboxRepository {

    private static final String INSERT = """
            INSERT INTO jira_outbox (
                id, ticket_ref, deployment_id, issue_lane, operation, effect_key, payload_ref,
                identity_ref, state, attempts, next_attempt_at, last_error_code,
                attempt_started_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPDATE = """
            UPDATE jira_outbox SET
                issue_lane = ?, state = ?, attempts = ?, next_attempt_at = ?,
                last_error_code = ?, attempt_started_at = ?
            WHERE id = ?""";

    private static final String RELEASE_STALE_CLAIMS = """
            UPDATE jira_outbox SET state = 'PENDING'
            WHERE state = 'CLAIMED' AND created_at < ?""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqlDialect dialect;
    private final String selectById;
    private final String selectByEffect;
    private final String selectInFlight;

    public JdbcOutboxRepository(DataSource dataSource, SqlDialect dialect) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        String columns = OutboxRowMapper.columns();
        this.selectById = "SELECT " + columns + " FROM jira_outbox WHERE id = ?";
        this.selectByEffect =
                "SELECT " + columns + " FROM jira_outbox WHERE ticket_ref = ? AND effect_key = ?";
        this.selectInFlight = "SELECT " + columns + " FROM jira_outbox"
                + " WHERE state = 'IN_FLIGHT' AND attempt_started_at < ?";
    }

    @Override
    public OutboxEntry append(OutboxEntry entry) {
        try {
            jdbc.update(INSERT,
                    entry.id().toString(),
                    entry.ticketRef(),
                    entry.deploymentId(),
                    entry.issueLane(),
                    entry.operation().name(),
                    entry.effectKey(),
                    OutboxRowMapper.encodePayloadRef(entry.payloadRef()),
                    entry.identityRef(),
                    entry.state().name(),
                    entry.attempts(),
                    Timestamp.from(entry.nextAttemptAt()),
                    entry.lastErrorCode(),
                    OutboxRowMapper.timestamp(entry.attemptStartedAt()),
                    Timestamp.from(entry.createdAt()));
            return entry;

        } catch (DataIntegrityViolationException e) {
            // Deliberately the broad type rather than DuplicateKeyException. Whether a duplicate
            // key surfaces as the narrower subclass depends on the vendor's error codes, and
            // relying on that mapping across three engines would be a subtle portability bug.
            // Re-reading is the definitive test: if the effect is already enqueued, return it.
            return findByEffect(entry.ticketRef(), entry.effectKey())
                    .orElseThrow(() -> new PersistenceException(
                            "integrity violation appending effect '" + entry.effectKey()
                                    + "' for ticket " + entry.ticketRef()
                                    + ", and no existing row explains it", e));
        }
    }

    @Override
    public List<OutboxEntry> claim(int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        // The transaction is part of the contract, not an optimisation: Oracle's claim is two
        // statements and its row locks must be held across both.
        return transactions.execute(status -> dialect.claimDue(jdbc, limit, now));
    }

    @Override
    public void save(OutboxEntry entry) {
        int updated = jdbc.update(UPDATE,
                entry.issueLane(),
                entry.state().name(),
                entry.attempts(),
                Timestamp.from(entry.nextAttemptAt()),
                entry.lastErrorCode(),
                OutboxRowMapper.timestamp(entry.attemptStartedAt()),
                entry.id().toString());

        if (updated == 0) {
            throw new PersistenceException("no outbox entry with id " + entry.id(), null);
        }
    }

    @Override
    public Optional<OutboxEntry> find(UUID id) {
        return jdbc.query(selectById, OutboxRowMapper.rowMapper(), id.toString())
                .stream().findFirst();
    }

    @Override
    public Optional<OutboxEntry> findByEffect(String ticketRef, String effectKey) {
        return jdbc.query(selectByEffect, OutboxRowMapper.rowMapper(), ticketRef, effectKey)
                .stream().findFirst();
    }

    @Override
    public List<OutboxEntry> findInFlightOlderThan(Instant threshold) {
        return jdbc.query(selectInFlight, OutboxRowMapper.rowMapper(), Timestamp.from(threshold));
    }

    @Override
    public int releaseStaleClaims(Instant claimedBefore) {
        // A dispatcher that died holding claims leaves rows CLAIMED with nothing working on them.
        // They are safe to release: CLAIMED means selected but not yet sent to Jira, so no
        // external effect can have happened. IN_FLIGHT is never auto-released, because there the
        // request may already have reached Jira — that call belongs to the ambiguity resolver.
        return jdbc.update(RELEASE_STALE_CLAIMS, Timestamp.from(claimedBefore));
    }

    public SqlDialect dialect() {
        return dialect;
    }
}
