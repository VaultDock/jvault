package dev.jvault.ingest.processing;

/**
 * Where a message is in processing. Mirrors {@code kafka_message_state.state}
 * (docs/07-kafka.md 7.4).
 */
public enum MessageState {

    RECEIVED,
    VALIDATED,
    CONTENT_STORED,
    JIRA_CREATED,
    LINKED,
    COMPLETE,

    /** The same business event already produced a ticket, on some other offset. */
    DUPLICATE,

    RETRYING,

    /** Terminal. The original is in the encrypted quarantine, not on the dead-letter topic. */
    DEAD_LETTERED;

    /**
     * Whether a redelivery of this message should do nothing.
     *
     * <p>{@code DUPLICATE} counts: recognising an event as already handled is a finished outcome,
     * not a failure to retry.
     */
    public boolean isTerminal() {
        return this == COMPLETE || this == DUPLICATE || this == DEAD_LETTERED;
    }
}
