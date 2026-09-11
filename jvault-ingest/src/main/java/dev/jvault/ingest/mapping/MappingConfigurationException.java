package dev.jvault.ingest.mapping;

/**
 * A mapping could not be parsed or validated.
 *
 * <p>Raised at configuration time, not at message time. A mapping that cannot work should be
 * rejected when an administrator saves it, rather than dead-lettering every event on a topic
 * until somebody notices.
 */
public class MappingConfigurationException extends RuntimeException {

    public MappingConfigurationException(String message) {
        super(message);
    }
}
