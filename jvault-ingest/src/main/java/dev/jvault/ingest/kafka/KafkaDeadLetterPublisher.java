package dev.jvault.ingest.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvault.ingest.processing.DeadLetterPublisher;
import dev.jvault.ingest.processing.DeadLetterRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;

/**
 * Publishes a dead-letter record to a Kafka topic.
 *
 * <p>The record carries identifiers, codes and field names — never the payload. A dead-letter
 * topic has different consumers, different retention and usually different access from the topic
 * it mirrors, so a copy of the message there is a copy of the content somewhere nobody decided
 * it should be. The payload goes to the quarantine store instead, encrypted, and what travels
 * here is the reference to it (docs/07-kafka.md 7.5).
 */
public final class KafkaDeadLetterPublisher implements DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    public KafkaDeadLetterPublisher(KafkaTemplate<String, byte[]> kafka, String topic) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    @Override
    public void publish(DeadLetterRecord record) {
        ObjectNode json = JSON.createObjectNode();
        json.put("correlationId", record.correlationId());
        json.put("mappingId", record.mappingId());
        json.put("occurredAt", record.occurredAt().toString());
        json.put("quarantineRef", record.quarantineRef());

        ObjectNode source = json.putObject("source");
        source.put("topic", record.source().topic());
        source.put("partition", record.source().partition());
        source.put("offset", record.source().offset());

        ObjectNode failure = json.putObject("failure");
        failure.put("stage", record.failure().stage());
        failure.put("code", record.failure().code());
        failure.put("attempts", record.failure().attempts());

        var problems = json.putArray("fieldProblems");
        record.fieldProblems().forEach(problem -> {
            ObjectNode entry = problems.addObject();
            // The field and the code, never the value that failed: the value is the content.
            entry.put("field", problem.field());
            entry.put("code", problem.code());
        });

        try {
            kafka.send(topic, record.correlationId(),
                            JSON.writeValueAsBytes(json))
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadLetterFailedException("interrupted publishing a dead letter", e);
        } catch (Exception e) {
            // Worth failing loudly: a dead letter that does not arrive is a message that has
            // silently disappeared, which is the one outcome this whole path exists to prevent.
            log.error("Could not publish a dead letter for {}", record.correlationId(), e);
            throw new DeadLetterFailedException("could not publish a dead letter", e);
        }
    }

    public static class DeadLetterFailedException extends RuntimeException {
        public DeadLetterFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
