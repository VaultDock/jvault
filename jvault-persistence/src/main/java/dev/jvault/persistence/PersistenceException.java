package dev.jvault.persistence;

/** A database operation failed. Carries identifiers and a message, never content. */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
