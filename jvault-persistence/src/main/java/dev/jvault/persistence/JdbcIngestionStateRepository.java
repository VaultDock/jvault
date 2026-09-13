package dev.jvault.persistence;

import dev.jvault.ingest.processing.IngestedMessage;
import dev.jvault.ingest.processing.IngestionStateRepository;
import dev.jvault.ingest.processing.MessageState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What has already been ingested, on Spring JDBC.
 *
 * <p>Kafka's own offsets cannot answer this. A commit and the work it covers are not one
 * transaction, so a consumer that dies between them redelivers work that already happened; the
 * offset row here is what makes that redelivery recognisable.
 *
 * <p>{@link #reserveDedupeKey} is the other half, and it is a write rather than a check. Two
 * consumers handling the same replayed event both pass a read-then-insert test and both create a
 * ticket; only the unique index stops them, and the duplicate-key failure is the signal.
 */
public final class JdbcIngestionStateRepository implements IngestionStateRepository {

    private static final String COLUMNS = """
            topic, partition_no, message_offset, mapping_id, dedupe_key, correlation_id, state,
            ticket_ref, attempts, last_error_code, quarantine_ref, received_at, updated_at""";

    private static final String INSERT = "INSERT INTO ingested_message (" + COLUMNS + ")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public JdbcIngestionStateRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public Optional<IngestedMessage> findByOffset(String topic, int partition, long offset) {
        return one("SELECT " + COLUMNS + " FROM ingested_message"
                        + " WHERE topic = ? AND partition_no = ? AND message_offset = ?",
                topic, partition, offset);
    }

    @Override
    public Optional<IngestedMessage> findByDedupeKey(String mappingId, String dedupeKey) {
        if (dedupeKey == null) {
            // No key means no claim to uniqueness; answering "found" for every keyless message
            // would make the first one a magnet for all the rest.
            return Optional.empty();
        }
        return one("SELECT " + COLUMNS + " FROM ingested_message"
                + " WHERE mapping_id = ? AND dedupe_key = ?", mappingId, dedupeKey);
    }

    @Override
    public boolean reserveDedupeKey(String mappingId, String dedupeKey, IngestedMessage message) {
        try {
            upsert(message.validated(dedupeKey));
            return true;
        } catch (DuplicateKeyException e) {
            // Somebody else owns this event. Losing the race is a normal outcome, not a fault.
            return false;
        }
    }

    @Override
    public void save(IngestedMessage message) {
        upsert(message);
    }

    /**
     * Written by primary key, which is the offset.
     *
     * <p>Deliberately not a blind insert: a message is saved several times as it moves through
     * its states, and the second save of the same offset is an update rather than a conflict.
     */
    private void upsert(IngestedMessage message) {
        int updated = jdbc.update("""
                        UPDATE ingested_message SET
                            mapping_id = ?, dedupe_key = ?, state = ?, ticket_ref = ?,
                            attempts = ?, last_error_code = ?, quarantine_ref = ?, updated_at = ?
                        WHERE topic = ? AND partition_no = ? AND message_offset = ?""",
                message.mappingId(), message.dedupeKey(), message.state().name(),
                message.ticketRef(), message.attempts(), message.lastErrorCode(),
                message.quarantineRef(), Timestamp.from(message.updatedAt()),
                message.topic(), message.partition(), message.offset());

        if (updated == 0) {
            jdbc.update(INSERT,
                    message.topic(), message.partition(), message.offset(), message.mappingId(),
                    message.dedupeKey(), message.correlationId(), message.state().name(),
                    message.ticketRef(), message.attempts(), message.lastErrorCode(),
                    message.quarantineRef(), Timestamp.from(message.receivedAt()),
                    Timestamp.from(message.updatedAt()));
        }
    }

    private Optional<IngestedMessage> one(String sql, Object... args) {
        List<IngestedMessage> found = jdbc.query(sql, JdbcIngestionStateRepository::map, args);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static IngestedMessage map(ResultSet rs, int rowNum) throws SQLException {
        String ticketRef = rs.getString("ticket_ref");
        String quarantineRef = rs.getString("quarantine_ref");

        return new IngestedMessage(
                rs.getString("topic"),
                rs.getInt("partition_no"),
                rs.getLong("message_offset"),
                rs.getString("dedupe_key"),
                rs.getString("correlation_id"),
                rs.getString("mapping_id"),
                MessageState.valueOf(rs.getString("state")),
                ticketRef == null ? null : ticketRef.trim(),
                rs.getInt("attempts"),
                rs.getString("last_error_code"),
                quarantineRef == null ? null : quarantineRef.trim(),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
