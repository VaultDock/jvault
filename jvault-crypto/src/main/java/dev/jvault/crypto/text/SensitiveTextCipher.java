package dev.jvault.crypto.text;

import dev.jvault.crypto.kms.DataKey;
import dev.jvault.crypto.kms.KeyManagementService;
import dev.jvault.domain.common.SensitiveValue;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Envelope encryption for short strings that are themselves sensitive.
 *
 * <p>An attachment called {@code 2026-Q3-layoffs-final.xlsx} tells you most of what the file
 * contains, so a filename column in the clear would undo a good deal of what encrypting the
 * bytes achieved (docs/04-data-model.md 4.2). The same is true of a display name derived from a
 * document title.
 *
 * <p>Deliberately not the content cipher. That one is a framed streaming format built for objects
 * measured in megabytes, and a filename is forty bytes; running it through a seekable framed
 * envelope would cost more in header than in payload. What the two share is the property that
 * matters — a per-value data key, wrapped by a KEK the database never sees.
 *
 * <p>Each value is bound to where it lives through the associated data. A ciphertext lifted from
 * one row and written into another does not decrypt, so a filename cannot be silently moved onto
 * a different object by anyone with write access to the table but not to the key.
 */
public final class SensitiveTextCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyManagementService kms;
    private final SecureRandom random = new SecureRandom();

    public SensitiveTextCipher(KeyManagementService kms) {
        this.kms = Objects.requireNonNull(kms, "kms");
    }

    /**
     * @param binding what this value belongs to — a content reference, a version id. It is
     *                authenticated but not secret, and it is not stored: the reader supplies the
     *                same binding, so a mismatch fails rather than decrypting something else
     */
    public Sealed seal(String keyRing, SensitiveValue value, String binding) {
        Objects.requireNonNull(value, "value");

        byte[] plaintext = value.reveal().getBytes(StandardCharsets.UTF_8);
        try (DataKey key = kms.generateDataKey(keyRing)) {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.plaintext(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));

            byte[] sealed = cipher.doFinal(plaintext);

            // Nonce first, then the GCM output. One column rather than two, because a nonce that
            // can be stored apart from its ciphertext can also be lost apart from it.
            byte[] combined = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, combined, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, combined, NONCE_BYTES, sealed.length);

            return new Sealed(key.kekId(), key.wrapped(), combined);
        } catch (Exception e) {
            throw new SealingException("could not seal a sensitive value", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** The value, or a failure — never a partially trusted result. */
    public SensitiveValue open(String keyRing, Sealed sealed, String binding, String label) {
        Objects.requireNonNull(sealed, "sealed");

        if (sealed.ciphertext().length <= NONCE_BYTES) {
            throw new SealingException("sealed value is too short to contain a nonce", null);
        }
        try (DataKey key = kms.unwrap(keyRing, sealed.kekId(), sealed.wrappedKey())) {
            byte[] combined = sealed.ciphertext();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.plaintext(), "AES"),
                    new GCMParameterSpec(TAG_BITS, combined, 0, NONCE_BYTES));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));

            byte[] plaintext = cipher.doFinal(
                    combined, NONCE_BYTES, combined.length - NONCE_BYTES);
            try {
                return SensitiveValue.of(new String(plaintext, StandardCharsets.UTF_8), label);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (KeyManagementService.KeyUnwrapException e) {
            throw e;
        } catch (Exception e) {
            // A tag failure and a wrong binding are the same event as far as a caller is
            // concerned: this ciphertext is not the one that belongs here.
            throw new SealingException("could not open a sensitive value", e);
        }
    }

    /**
     * @param wrappedKey the data key under the KEK. The plaintext key exists only for the length
     *                   of a call and is never part of this record
     */
    public record Sealed(String kekId, byte[] wrappedKey, byte[] ciphertext) {

        public Sealed {
            Objects.requireNonNull(kekId, "kekId");
            wrappedKey = wrappedKey.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] wrappedKey() {
            return wrappedKey.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        /** Says nothing about what was sealed, which is the whole point of sealing it. */
        @Override
        public String toString() {
            return "Sealed[kek=" + kekId + ", " + ciphertext.length + " bytes]";
        }
    }

    public static class SealingException extends RuntimeException {
        public SealingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
