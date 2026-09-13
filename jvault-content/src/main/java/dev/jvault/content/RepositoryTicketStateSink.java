package dev.jvault.content;

import dev.jvault.outbox.TicketStateSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the outcome of a Jira write back onto the ticket.
 *
 * <p>Without this the dispatcher does its work and nobody learns the result: the issue exists in
 * Jira and the ticket still says it is pending, which is exactly as useless as not having
 * dispatched at all.
 *
 * <p>Each method is idempotent on purpose. A dispatch can be retried after its Jira call
 * succeeded but before its acknowledgement was recorded, so arriving twice at "this ticket is
 * active" has to be ordinary rather than an error.
 */
public final class RepositoryTicketStateSink implements TicketStateSink {

    private static final Logger log = LoggerFactory.getLogger(RepositoryTicketStateSink.class);

    private final TicketRepository tickets;

    public RepositoryTicketStateSink(TicketRepository tickets) {
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    @Override
    public void ticketBecameActive(String ticketRef, String issueId, String issueKey,
                                   Instant when) {
        update(ticketRef, ticket -> new TicketRecord(
                ticket.ticketRef(), ticket.deploymentId(), ticket.projectKey(),
                ticket.issueTypeId(), ticket.dedupeKey(), ticket.correlationId(), ticket.origin(),
                ticket.jiraFields(), ticket.externalContentRefs(), TicketRecord.State.ACTIVE,
                issueId, issueKey, ticket.createdAt()));

        log.info("Ticket {} is now {} in Jira", ticketRef, issueKey);
    }

    @Override
    public void ticketBecameAmbiguous(String ticketRef, AmbiguityContext context) {
        // The Jira call's outcome is unknown: it may have created an issue, it may not. The
        // reconciler searches for the issue by correlation id later; until then the ticket must
        // not be retried, because retrying is how one event becomes two issues.
        update(ticketRef, ticket -> ticket.withFields(ticket.jiraFields(),
                ticket.externalContentRefs(), TicketRecord.State.AMBIGUOUS));

        log.warn("Ticket {} is ambiguous: a Jira create may or may not have happened, "
                + "correlationId={}", ticketRef, context.correlationId());
    }

    @Override
    public void ticketFailed(String ticketRef, String errorCode, Instant when) {
        update(ticketRef, ticket -> ticket.withFields(ticket.jiraFields(),
                ticket.externalContentRefs(), TicketRecord.State.FAILED));

        // The code, not a message: messages from Jira can quote the request, and the request
        // carries field values.
        log.error("Ticket {} failed permanently: {}", ticketRef, errorCode);
    }

    private void update(String ticketRef, java.util.function.UnaryOperator<TicketRecord> change) {
        Optional<TicketRecord> found = tickets.find(ticketRef);
        if (found.isEmpty()) {
            // Nothing sensible to do, and throwing would make the dispatcher retry an effect
            // whose ticket has gone. Worth a line in the log, because it should not happen.
            log.error("No ticket {} to record an outcome against", ticketRef);
            return;
        }
        tickets.save(change.apply(found.get()));
    }
}
