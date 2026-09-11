package dev.jvault.crypto.envelope;

import java.util.Objects;

/**
 * Decides which wrapped data key to unwrap for an object.
 *
 * <p>This exists because of key rotation. Rewrapping a data key under a new key-encryption key
 * updates the {@code content_version} row and deliberately does <em>not</em> rewrite the stored
 * object — that is the entire reason envelope encryption is worth its complexity, and rewriting
 * ten terabytes to rotate a KEK would make rotation something nobody ever does
 * (docs/09-encryption.md 9.5).
 *
 * <p>So after a rotation the header inside the object holds a <em>stale</em> wrapped key. The
 * database is authoritative; the header is the disaster-recovery fallback for an object found
 * without its metadata row.
 *
 * <p>The header's <em>hash</em> is still bound into the associated data either way, so the stored
 * header remains authenticated and cannot be altered even though its wrapped key is no longer the
 * one in use.
 */
@FunctionalInterface
public interface KeyLocator {

    WrappedKey locate(EnvelopeHeader header);

    record WrappedKey(String kekId, byte[] bytes) {
        public WrappedKey {
            Objects.requireNonNull(kekId, "kekId");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * Uses the key recorded inside the object.
     *
     * <p>Correct until the first rotation, and the right fallback for recovering an object whose
     * database row is gone. Not correct for normal reads in a system that rotates keys.
     */
    static KeyLocator fromHeader() {
        return header -> new WrappedKey(header.kekId(), header.wrappedDataKey());
    }

    /** Uses the current wrapped key from {@code content_version}. The normal read path. */
    static KeyLocator of(String kekId, byte[] wrappedKey) {
        var key = new WrappedKey(kekId, wrappedKey);
        return header -> key;
    }
}
