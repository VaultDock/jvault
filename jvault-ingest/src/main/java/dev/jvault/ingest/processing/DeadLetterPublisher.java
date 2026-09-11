package dev.jvault.ingest.processing;

/**
 * Publishes dead-letter records.
 *
 * <p>Takes a {@link DeadLetterRecord} and nothing else, by design: there is no overload accepting
 * a payload, so the "just include the message so we can debug it" shortcut is not available
 * without changing this interface — which is a conversation rather than an accident.
 */
public interface DeadLetterPublisher {

    void publish(DeadLetterRecord record);
}
