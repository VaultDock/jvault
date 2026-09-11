package dev.jvault.crypto.kms;

/**
 * Wraps and unwraps data keys. The key-encryption keys never leave the implementation.
 *
 * <p>Deliberately four methods. A narrow port keeps the adapters small, and the adapters are
 * where the deployment-specific risk lives: under decision D2 the production implementations are
 * HashiCorp Vault Transit and PKCS#11 against an on-premises HSM, neither of which should need to
 * know anything about jvault beyond "wrap these 32 bytes".
 *
 * <p>Implementations must fail loudly. Returning a null or empty key, or silently substituting a
 * different key ring, would produce objects that encrypt successfully and can never be read.
 */
public interface KeyManagementService {

    /**
     * Generates a fresh data key and returns it in both plaintext and wrapped form.
     *
     * <p>One per object version. Reusing a data key across objects would make
     * {@link #rewrap} useless for containing a compromise, because the blast radius of one key
     * would no longer be one object.
     *
     * @throws KmsUnavailableException always, rather than returning a degraded result
     */
    DataKey generateDataKey(String keyRing);

    /**
     * Recovers a data key from its wrapped form.
     *
     * @throws KmsUnavailableException if the key manager cannot be reached
     * @throws KeyUnwrapException      if the wrapped key is not valid under this key ring —
     *                                 corruption, a tampered header, or the wrong key ring
     */
    DataKey unwrap(String keyRing, String kekId, byte[] wrappedKey);

    /**
     * Re-wraps a data key under a new key-encryption key without touching the content.
     *
     * <p>This is the entire point of envelope encryption: rotating a KEK is a metadata operation
     * over {@code content_version} rows, not a rewrite of every object (docs/09-encryption.md 9.5).
     */
    byte[] rewrap(String fromKeyRing, String toKeyRing, String kekId, byte[] wrappedKey);

    /** Current KEK identifier for a ring, recorded in the object header so it is self-describing. */
    String currentKekId(String keyRing);

    /** Thrown when a wrapped key cannot be recovered. Distinct from unavailability. */
    class KeyUnwrapException extends RuntimeException {
        public KeyUnwrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
