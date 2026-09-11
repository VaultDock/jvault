package dev.jvault.crypto.kms;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single-use content encryption key: the plaintext form for encrypting, and the wrapped form
 * for storing alongside the object.
 *
 * <p>{@link AutoCloseable} on purpose. The plaintext key should exist for as long as one encrypt
 * or decrypt takes and no longer, and {@code try (DataKey key = ...)} makes that the natural way
 * to write the call site. Zeroing is best-effort — the JVM may have copied the array during a GC
 * move and we cannot reach those copies — but best-effort narrows the window a heap dump has to
 * hit, which is worth having.
 */
public final class DataKey implements AutoCloseable {

    private final String kekId;
    private final byte[] plaintext;
    private final byte[] wrapped;
    private boolean destroyed;

    public DataKey(String kekId, byte[] plaintext, byte[] wrapped) {
        this.kekId = Objects.requireNonNull(kekId, "kekId");
        this.plaintext = Objects.requireNonNull(plaintext, "plaintext").clone();
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped").clone();
    }

    /** Identifier of the key-encryption key. Not the key itself; safe to store and to log. */
    public String kekId() {
        return kekId;
    }

    public byte[] plaintext() {
        if (destroyed) {
            throw new IllegalStateException("data key has been destroyed");
        }
        return plaintext;
    }

    /** The form that goes into the object header and the {@code content_version} row. */
    public byte[] wrapped() {
        return wrapped.clone();
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void close() {
        Arrays.fill(plaintext, (byte) 0);
        destroyed = true;
    }

    /** Never prints key material, destroyed or not. */
    @Override
    public String toString() {
        return "DataKey[kek=" + kekId + ", destroyed=" + destroyed + "]";
    }
}
