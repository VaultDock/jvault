package dev.jvault.ingest.processing;

import java.time.Instant;
import java.util.Objects;

/**
 * The durable record of one message's progress. Mirrors {@code kafka_message_state}
 * (docs/04-data-model.md 4.8).
 *
 * <p>Committed before the Kafka offset, so a crash resumes from here rather than starting over.
 *
 * @param lastErrorCode an error <em>class</em>, never error text — this reaches the status API,
 *                      the logs and the operator dashboard
 */
public record IngestedMessage(String topic,
                              int partition,
                              long offset,
                              String dedupeKey,
                              String correlationId,
                              String mappingId,
                              MessageState state,
                              String ticketRef,
                              int attempts,
                              String lastErrorCode,
                              String quarantineRef,
                              Instant receivedAt,
                              Instant updatedAt) {

    public IngestedMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(state, "state");
    }

    public static IngestedMessage received(String topic, int partition, long offset,
                                           String correlationId, String mappingId, Instant now) {
        return new IngestedMessage(topic, partition, offset, null, correlationId, mappingId,
                MessageState.RECEIVED, null, 0, null, null, now, now);
    }

    public IngestedMessage validated(String dedupeKey) {
        return new IngestedMessage(topic, partition, offset, dedupeKey, correlationId, mappingId,
                MessageState.VALIDATED, ticketRef, attempts, lastErrorCode, quarantineRef,
                receivedAt, updatedAt);
    }

    public IngestedMessage completed(String ticketRef) {
        return new IngestedMessage(topic, partition, offset, dedupeKey, correlationId, mappingId,
                MessageState.COMPLETE, ticketRef, attempts, null, quarantineRef,
                receivedAt, updatedAt);
    }

    /**
     * Marks this message as already handled by another offset.
     *
     * <p>The dedupe key is deliberately dropped. {@code kafka_message_state} has a unique
     * constraint on {@code (mapping_id, dedupe_key)}, so only the row that won the race may hold
     * it; a losing row that kept the key could not be inserted at all. What it keeps instead is a
     * pointer to the ticket the winner produced, which is what a caller asking "what happened to
     * this message" actually needs.
     */
    public IngestedMessage duplicate(String existingTicketRef) {
        return new IngestedMessage(topic, partition, offset, null, correlationId, mappingId,
                MessageState.DUPLICATE, existingTicketRef, attempts, null, quarantineRef,
                receivedAt, updatedAt);
    }

    public IngestedMessage retrying(String errorCode) {
        return new IngestedMessage(topic, partition, offset, dedupeKey, correlationId, mappingId,
                MessageState.RETRYING, ticketRef, attempts + 1, errorCode, quarantineRef,
                receivedAt, updatedAt);
    }

    public IngestedMessage deadLettered(String errorCode, String quarantineRef) {
        return new IngestedMessage(topic, partition, offset, dedupeKey, correlationId, mappingId,
                MessageState.DEAD_LETTERED, ticketRef, attempts + 1, errorCode, quarantineRef,
                receivedAt, updatedAt);
    }

    /** Identifiers only. */
    @Override
    public String toString() {
        return "IngestedMessage[" + topic + "/" + partition + "/" + offset + ", " + state
                + ", correlationId=" + correlationId + "]";
    }
}
