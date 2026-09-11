package dev.jvault.ingest.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.content.TicketCreationService;
import dev.jvault.ingest.mapping.EventMapper;
import dev.jvault.ingest.mapping.EventMapping;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Processes one consumed message.
 *
 * <p>The state row is committed <em>before</em> the Kafka offset, always. Everything before that
 * point is replayable; everything after it is already recorded. That ordering is what makes a
 * consumer crash survivable without either losing a message or creating a second ticket
 * (docs/07-kafka.md 7.4).
 *
 * <p>Deduplication is two checks, and they catch different things. The offset check catches
 * <em>redelivery</em> — the same message handed to us twice after a crash or a rebalance. The
 * business-key check catches <em>republication</em> — the same event arriving on a new offset
 * because a producer retried or a topic was replayed. A system with only the first would create
 * duplicate tickets on any replay; with only the second it would redo work after every rebalance.
 *
 * <p>Guarantees, stated honestly: at-least-once delivery, and <em>effectively-once</em> ticket
 * creation per business key. Not exactly-once — that is not available across Kafka, Jira and
 * storage, and claiming it would be false.
 */
public final class MessageProcessor {

    private final EventMapping mapping;
    private final EventMapper mapper;
    private final TicketCreationService creation;
    private final IngestionStateRepository state;
    private final QuarantineStore quarantine;
    private final DeadLetterPublisher deadLetters;
    private final ObjectMapper json;
    private final Clock clock;

    public MessageProcessor(EventMapping mapping,
                            TicketCreationService creation,
                            IngestionStateRepository state,
                            QuarantineStore quarantine,
                            DeadLetterPublisher deadLetters,
                            Clock clock) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.mapper = new EventMapper(mapping);
        this.creation = Objects.requireNonNull(creation, "creation");
        this.state = Objects.requireNonNull(state, "state");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.json = new ObjectMapper();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Outcome process(ConsumedMessage message) {
        Objects.requireNonNull(message, "message");

        Optional<IngestedMessage> existing = state.findByOffset(
                message.topic(), message.partition(), message.offset());
        if (existing.isPresent() && existing.get().state().isTerminal()) {
            // A redelivery of something already finished. Acknowledge and do nothing — in
            // particular, do not call Jira.
            return new Outcome(existing.get().state(), existing.get().ticketRef(), true);
        }

        String correlationId = message.correlationId();
        IngestedMessage record = existing.orElseGet(() -> IngestedMessage.received(
                message.topic(), message.partition(), message.offset(),
                correlationId, mapping.id(), clock.instant()));

        JsonNode event;
        try {
            event = json.readTree(message.payload());
        } catch (JsonProcessingException e) {
            // Malformed before any mapping, any content write, any Jira call.
            return deadLetter(message, record, "PARSE", "MALFORMED_JSON", List.of());
        }

        EventMapper.Mapped mapped;
        try {
            mapped = mapper.map(event, correlationId);
        } catch (EventMapper.EventValidationException e) {
            return deadLetter(message, record, "VALIDATION", "SCHEMA_VIOLATION", e.problems());
        }

        IngestedMessage validated = record.validated(mapped.dedupeKey());

        // Claim the business key *before* persisting a row that carries it. The state table has a
        // unique constraint on (mapping_id, dedupe_key), so only the winner may hold it —
        // writing the row first would violate that constraint for every republication.
        if (!state.reserveDedupeKey(mapping.id(), mapped.dedupeKey(), validated)) {
            IngestedMessage duplicate = validated.duplicate(
                    state.findByDedupeKey(mapping.id(), mapped.dedupeKey())
                            .map(IngestedMessage::ticketRef).orElse(null));
            state.save(duplicate);
            return new Outcome(duplicate.state(), duplicate.ticketRef(), true);
        }
        state.save(validated);

        try {
            TicketCreationService.Result result = creation.create(mapped.command());
            IngestedMessage complete = validated.completed(result.ticket().ticketRef());
            state.save(complete);
            return new Outcome(complete.state(), complete.ticketRef(), result.duplicate());

        } catch (RuntimeException e) {
            // Transient failures belong to the retry tiers, not here: the outbox owns delivery to
            // Jira, so reaching this point means the command itself could not be accepted.
            IngestedMessage retrying = validated.retrying(classify(e));
            state.save(retrying);
            throw new ProcessingFailedException(retrying, e);
        }
    }

    private Outcome deadLetter(ConsumedMessage message,
                               IngestedMessage record,
                               String stage,
                               String code,
                               List<EventMapper.FieldProblem> problems) {
        // The original goes to the encrypted quarantine, not to the dead-letter topic. Replay
        // reads from quarantine and is audited.
        String quarantineRef = null;
        try {
            quarantineRef = quarantine.quarantine(record.correlationId(),
                    message.payload().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Losing the original is bad, but publishing it to escape that would be worse.
            code = code + "_UNQUARANTINED";
        }

        deadLetters.publish(new DeadLetterRecord(
                record.correlationId(),
                new DeadLetterRecord.Source(message.topic(), message.partition(), message.offset()),
                mapping.id(),
                new DeadLetterRecord.Failure(stage, code, record.attempts() + 1),
                problems,
                quarantineRef,
                clock.instant()));

        IngestedMessage dead = record.deadLettered(code, quarantineRef);
        state.save(dead);
        return new Outcome(dead.state(), null, false);
    }

    private static String classify(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    /**
     * @param acknowledgeable whether the Kafka offset may be committed. Always true here: a
     *                        message that reached a terminal state, duplicate included, is done
     */
    public record Outcome(MessageState state, String ticketRef, boolean acknowledgeable) {
    }

    /** Raised so the consumer routes the message to a retry tier rather than committing it. */
    public static class ProcessingFailedException extends RuntimeException {

        private final IngestedMessage message;

        public ProcessingFailedException(IngestedMessage message, Throwable cause) {
            super("processing failed at " + message.state(), cause);
            this.message = message;
        }

        public IngestedMessage message() {
            return message;
        }
    }

    /**
     * One message as the consumer hands it over.
     *
     * @param payload the raw event. The only place it exists in this module, and it never reaches
     *                a log, a metric, an error message or a dead-letter record
     */
    public record ConsumedMessage(String topic,
                                  int partition,
                                  long offset,
                                  String key,
                                  String payload,
                                  String correlationId) {

        /** Identifiers only, so a consumer can log the message it is working on. */
        @Override
        public String toString() {
            return "ConsumedMessage[" + topic + "/" + partition + "/" + offset
                    + ", correlationId=" + correlationId + "]";
        }
    }
}
