package dev.jvault.crypto.kms;

/**
 * The key manager could not be reached, or refused.
 *
 * <p>Always fatal to the operation in progress. There is no degraded mode in which content is
 * stored unencrypted because the key manager was down (docs/01-requirements.md FR-ENC-5): the
 * whole point of application-level encryption is that a storage backend never sees plaintext,
 * and an availability-driven exception to that would be the one time it mattered.
 */
public class KmsUnavailableException extends RuntimeException {

    private final String keyRing;

    public KmsUnavailableException(String keyRing, String message, Throwable cause) {
        super("key manager unavailable for key ring '" + keyRing + "': " + message, cause);
        this.keyRing = keyRing;
    }

    public String keyRing() {
        return keyRing;
    }
}
