package dev.jvault.ingest.kafka;

import dev.jvault.ingest.processing.MessageProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The Kafka half of ingestion: take a record off a topic, hand it to the processor, decide
 * whether to acknowledge.
 *
 * <p>Everything that decides what a message <em>means</em> lives in
 * {@link MessageProcessor} and knows nothing about Kafka, which is what makes a message arriving
 * on a topic and a request arriving over HTTP produce the same ticket by the same rules
 * (docs/06-rest-api.md 6.3).
 *
 * <p>Acknowledgement is manual and follows the processor's answer rather than the absence of an
 * exception. A message that failed in a way worth retrying must not have its offset committed;
 * one that was dead-lettered must, because redelivering a poison message forever is how a
 * partition stops moving.
 */
public final class KafkaMessageListener implements AcknowledgingMessageListener<String, byte[]> {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageListener.class);

    /** The header a producer uses to carry its own trace id, if it has one. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final MessageProcessor processor;

    public KafkaMessageListener(MessageProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        var message = new MessageProcessor.ConsumedMessage(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value() == null ? "" : new String(record.value(), StandardCharsets.UTF_8),
                correlationId(record));

        MessageProcessor.Outcome outcome;
        try {
            outcome = processor.process(message);
        } catch (RuntimeException e) {
            // The processor dead-letters what it can classify. Anything reaching here is a fault
            // in jvault rather than in the message, so the offset stays put: committing past a
            // bug loses the message, and the alternative is a partition that visibly stalls.
            log.error("Ingestion failed for {}; not acknowledging", message, e);
            return;
        }

        if (outcome.acknowledgeable()) {
            acknowledgment.acknowledge();
        } else {
            // Retried by redelivery rather than by a loop here: a listener that sleeps and tries
            // again holds the partition and eventually trips the consumer's poll timeout, which
            // triggers a rebalance and redelivers it anyway — slower, and to everyone else's
            // cost.
            log.warn("Ingestion of {} ended in {}; leaving the offset uncommitted for redelivery",
                    message, outcome.state());
        }
    }

    /**
     * The producer's correlation id, or one made up here.
     *
     * <p>Never absent: the id is what ties a ticket back to the event that caused it, and a
     * message without one still has to be traceable through the dead-letter topic.
     */
    private static String correlationId(ConsumerRecord<String, byte[]> record) {
        var header = record.headers().lastHeader(CORRELATION_HEADER);
        if (header != null && header.value() != null && header.value().length > 0) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return UUID.randomUUID().toString();
    }
}
