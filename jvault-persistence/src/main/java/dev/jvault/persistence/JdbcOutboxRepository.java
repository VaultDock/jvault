package dev.jvault.persistence;

import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxRepository;
import dev.jvault.outbox.OutboxState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of the outbox, across all three supported engines.
 *
 * <p>Two contract properties from {@link OutboxRepository} are provided here rather than assumed,
 * because the whole dispatcher rests on them:
 *
 * <ul>
 *   <li><strong>{@code append} is idempotent</strong> on {@code (ticket_ref, effect_key)}. The
 *       unique constraint is the mechanism and the duplicate-key exception is the signal — not a
 *       read-then-write check, which would race between two nodes enqueuing the same effect.</li>
 *   <li><strong>{@code claim} never hands one row to two workers.</strong> Delegated to
 *       {@link SqlDialect#claimDue}, because the skip-locked semantics differ per engine and are
 *       the one thing that must not be abstracted away.</li>
 * </ul>
 *
 * <p>Plain JDBC, deliberately. The outbox is six statements and its correctness lives in their
 * exact locking behaviour; a mapping framework would add indirection over precisely the part that
 * needs to stay readable.
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

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String selectById;
    private final String selectByEffect;
    private final String selectInFlight;

    public JdbcOutboxRepository(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        String columns = OutboxRowMapper.columns();
        this.selectById = "SELECT " + columns + " FROM jira_outbox WHERE id = ?";
        this.selectByEffect =
                "SELECT " + columns + " FROM jira_outbox WHERE ticket_ref = ? AND effect_key = ?";
        this.selectInFlight = "SELECT " + columns + " FROM jira_outbox"
                + " WHERE state = 'IN_FLIGHT' AND attempt_started_at < ?";
    }

    @Override
    public OutboxEntry append(OutboxEntry entry) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                bindInsert(statement, entry);
                statement.executeUpdate();
                return entry;
            } catch (SQLException e) {
                if (!dialect.isUniqueViolation(e)) {
                    throw e;
                }
                // Already enqueued. Returning the existing row makes enqueueing safe to repeat,
                // which is what lets callers retry without tracking what they already sent.
                return findByEffect(entry.ticketRef(), entry.effectKey())
                        .orElseThrow(() -> new PersistenceException(
                                "unique violation on (" + entry.ticketRef() + ", "
                                        + entry.effectKey() + ") but no row found", e));
            }
        } catch (SQLException e) {
            throw new PersistenceException("could not append outbox entry", e);
        }
    }

    @Override
    public List<OutboxEntry> claim(int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<OutboxEntry> claimed = dialect.claimDue(connection, limit, now);
                connection.commit();
                return claimed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new PersistenceException("could not claim outbox entries", e);
        }
    }

    @Override
    public void save(OutboxEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            statement.setString(1, entry.issueLane());
            statement.setString(2, entry.state().name());
            statement.setInt(3, entry.attempts());
            statement.setTimestamp(4, OutboxRowMapper.timestamp(entry.nextAttemptAt()));
            statement.setString(5, entry.lastErrorCode());
            statement.setTimestamp(6, OutboxRowMapper.timestamp(entry.attemptStartedAt()));
            statement.setString(7, entry.id().toString());

            if (statement.executeUpdate() == 0) {
                throw new PersistenceException("no outbox entry with id " + entry.id(), null);
            }
        } catch (SQLException e) {
            throw new PersistenceException("could not save outbox entry " + entry.id(), e);
        }
    }

    @Override
    public Optional<OutboxEntry> find(UUID id) {
        return queryOne(selectById, statement -> statement.setString(1, id.toString()));
    }

    @Override
    public Optional<OutboxEntry> findByEffect(String ticketRef, String effectKey) {
        return queryOne(selectByEffect, statement -> {
            statement.setString(1, ticketRef);
            statement.setString(2, effectKey);
        });
    }

    @Override
    public List<OutboxEntry> findInFlightOlderThan(Instant threshold) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(selectInFlight)) {
            statement.setTimestamp(1, OutboxRowMapper.timestamp(threshold));
            try (ResultSet rs = statement.executeQuery()) {
                return OutboxRowMapper.mapAll(rs);
            }
        } catch (SQLException e) {
            throw new PersistenceException("could not read in-flight entries", e);
        }
    }

    @Override
    public int releaseStaleClaims(Instant claimedBefore) {
        // A dispatcher that died holding claims leaves rows CLAIMED with nothing working on them.
        // They are safe to release: CLAIMED means selected but not yet sent to Jira, so no
        // external effect can have happened. IN_FLIGHT is the state that is never auto-released,
        // because there the request may already have reached Jira.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE jira_outbox SET state = 'PENDING'
                     WHERE state = 'CLAIMED' AND created_at < ?""")) {
            statement.setTimestamp(1, OutboxRowMapper.timestamp(claimedBefore));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("could not release stale claims", e);
        }
    }

    private Optional<OutboxEntry> queryOne(String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(OutboxRowMapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("query failed", e);
        }
    }

    private static void bindInsert(PreparedStatement statement, OutboxEntry entry)
            throws SQLException {
        statement.setString(1, entry.id().toString());
        statement.setString(2, entry.ticketRef());
        statement.setString(3, entry.deploymentId());
        statement.setString(4, entry.issueLane());
        statement.setString(5, entry.operation().name());
        statement.setString(6, entry.effectKey());
        statement.setString(7, OutboxRowMapper.encodePayloadRef(entry.payloadRef()));
        statement.setString(8, entry.identityRef());
        statement.setString(9, entry.state().name());
        statement.setInt(10, entry.attempts());
        statement.setTimestamp(11, OutboxRowMapper.timestamp(entry.nextAttemptAt()));
        statement.setString(12, entry.lastErrorCode());
        statement.setTimestamp(13, OutboxRowMapper.timestamp(entry.attemptStartedAt()));
        statement.setTimestamp(14, OutboxRowMapper.timestamp(entry.createdAt()));
    }

    /** States a claim may take an entry from, kept beside the SQL that names them. */
    static boolean isClaimable(OutboxState state) {
        return state.isClaimable();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
