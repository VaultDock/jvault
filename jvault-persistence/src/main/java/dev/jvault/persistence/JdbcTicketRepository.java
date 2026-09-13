package dev.jvault.persistence;

import dev.jvault.content.TicketCommand;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.TicketRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tickets on Spring JDBC.
 *
 * <p>The interesting method is {@link #reserve}, and the interesting thing about it is that it
 * does not read before it writes. Two nodes consuming the same replayed Kafka message would both
 * pass a read-then-insert check and both create an issue; the unique index on the dedupe key is
 * what actually prevents that, and the duplicate-key failure is the signal rather than an error.
 *
 * <p>A ticket's fields and external parts live in child tables rather than in a JSON column,
 * because two of the three supported engines would need different JSON syntax and the query
 * planner would treat them differently (decision D5). They are rewritten wholesale on save: a
 * ticket has a handful of fields, and a diff would be more code and more ways to be wrong.
 */
public final class JdbcTicketRepository implements TicketRepository {

    private static final String INSERT = """
            INSERT INTO ticket_record (
                ticket_ref, deployment_id, project_key, issue_type_id, dedupe_key,
                correlation_id, origin_channel, origin_actor_id, origin_actor_name,
                acting_identity, state, jira_issue_id, jira_issue_key, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPDATE = """
            UPDATE ticket_record SET
                state = ?, jira_issue_id = ?, jira_issue_key = ?, correlation_id = ?
            WHERE ticket_ref = ?""";

    private static final String COLUMNS = """
            ticket_ref, deployment_id, project_key, issue_type_id, dedupe_key, correlation_id,
            origin_channel, origin_actor_id, origin_actor_name, acting_identity, state,
            jira_issue_id, jira_issue_key, created_at""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcTicketRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public Reservation reserve(TicketRecord candidate) {
        try {
            insert(candidate);
            return new Reservation(candidate, true);
        } catch (DuplicateKeyException e) {
            // Either this exact ticket ref was inserted already, or another node won the race for
            // the dedupe key. Both mean the same thing to a caller: someone else owns it.
            return new Reservation(existing(candidate), false);
        }
    }

    private TicketRecord existing(TicketRecord candidate) {
        return find(candidate.ticketRef())
                .or(() -> candidate.dedupeKey() == null ? Optional.empty()
                        : findByDedupeKey(candidate.deploymentId(), candidate.projectKey(),
                                candidate.dedupeKey()))
                .orElseThrow(() -> new PersistenceException(
                        "a ticket was rejected as a duplicate but cannot be found; the dedupe "
                                + "index and the lookup disagree, which should be impossible",
                        null));
    }

    private void insert(TicketRecord ticket) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(INSERT,
                    ticket.ticketRef(),
                    ticket.deploymentId(),
                    ticket.projectKey(),
                    ticket.issueTypeId(),
                    ticket.dedupeKey(),
                    ticket.correlationId(),
                    ticket.origin() == null ? "API" : ticket.origin().channel().name(),
                    ticket.origin() == null ? null : ticket.origin().actorId(),
                    ticket.origin() == null ? null : ticket.origin().actorDisplayName(),
                    ticket.origin() == null ? null : ticket.origin().identityRef(),
                    ticket.state().name(),
                    ticket.jiraIssueId(),
                    ticket.jiraIssueKey(),
                    Timestamp.from(ticket.createdAt()));
            writeChildren(ticket);
        });
    }

    @Override
    public void save(TicketRecord ticket) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update(UPDATE,
                    ticket.state().name(),
                    ticket.jiraIssueId(),
                    ticket.jiraIssueKey(),
                    ticket.correlationId(),
                    ticket.ticketRef());
            if (updated == 0) {
                throw new PersistenceException(
                        "ticket " + ticket.ticketRef() + " has vanished between reserve and save",
                        null);
            }
            writeChildren(ticket);
        });
    }

    /** Fields and part references, rewritten rather than diffed. */
    private void writeChildren(TicketRecord ticket) {
        jdbc.update("DELETE FROM ticket_field WHERE ticket_ref = ?", ticket.ticketRef());
        for (Map.Entry<String, String> field : ticket.jiraFields().entrySet()) {
            jdbc.update("INSERT INTO ticket_field (ticket_ref, field_key, field_value)"
                            + " VALUES (?, ?, ?)",
                    ticket.ticketRef(), field.getKey(), field.getValue());
        }

        jdbc.update("DELETE FROM ticket_external_part WHERE ticket_ref = ?", ticket.ticketRef());
        List<String> refs = ticket.externalContentRefs();
        for (int i = 0; i < refs.size(); i++) {
            jdbc.update("INSERT INTO ticket_external_part (ticket_ref, ordinal, content_ref)"
                    + " VALUES (?, ?, ?)", ticket.ticketRef(), i, refs.get(i));
        }
    }

    @Override
    public Optional<TicketRecord> find(String ticketRef) {
        return one("SELECT " + COLUMNS + " FROM ticket_record WHERE ticket_ref = ?", ticketRef);
    }

    @Override
    public Optional<TicketRecord> findByDedupeKey(String deploymentId,
                                                  String projectKey,
                                                  String dedupeKey) {
        if (dedupeKey == null) {
            // No key means no claim to uniqueness. Answering "found" for every keyless ticket
            // would turn the first one into a magnet for every later one.
            return Optional.empty();
        }
        return one("SELECT " + COLUMNS + " FROM ticket_record"
                        + " WHERE deployment_id = ? AND project_key = ? AND dedupe_key = ?",
                deploymentId, projectKey, dedupeKey);
    }

    private Optional<TicketRecord> one(String sql, Object... args) {
        List<TicketRecord> found = jdbc.query(sql, this::mapTicket, args);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private TicketRecord mapTicket(ResultSet rs, int rowNum) throws SQLException {
        String ticketRef = rs.getString("ticket_ref").trim();

        var fields = new LinkedHashMap<String, String>();
        jdbc.query("SELECT field_key, field_value FROM ticket_field WHERE ticket_ref = ?"
                        + " ORDER BY field_key",
                (RowCallbackHandler) row -> fields.put(row.getString(1), row.getString(2)),
                ticketRef);

        var parts = new ArrayList<String>();
        jdbc.query("SELECT content_ref FROM ticket_external_part WHERE ticket_ref = ?"
                        + " ORDER BY ordinal",
                (RowCallbackHandler) row -> parts.add(row.getString(1).trim()), ticketRef);

        return new TicketRecord(
                ticketRef,
                rs.getString("deployment_id"),
                rs.getString("project_key"),
                rs.getString("issue_type_id"),
                rs.getString("dedupe_key"),
                rs.getString("correlation_id"),
                origin(rs),
                fields,
                parts,
                TicketRecord.State.valueOf(rs.getString("state")),
                rs.getString("jira_issue_id"),
                rs.getString("jira_issue_key"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static TicketCommand.Origin origin(ResultSet rs) throws SQLException {
        return new TicketCommand.Origin(
                TicketCommand.Origin.Channel.valueOf(rs.getString("origin_channel")),
                rs.getString("origin_actor_id"),
                rs.getString("origin_actor_name"),
                rs.getString("acting_identity"));
    }
}
