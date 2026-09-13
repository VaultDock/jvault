package dev.jvault.crypto.kms;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A key manager that holds its key-encryption keys in this process's memory.
 *
 * <p><strong>Development and test only.</strong> It is not a production key manager and must not
 * be configured as one: the KEKs live in the same heap as the plaintext they protect, which
 * defeats the separation that makes envelope encryption worth doing
 * (docs/09-encryption.md 9.6). Under decision D2 the production implementations are HashiCorp
 * Vault Transit and PKCS#11 against an on-premises HSM.
 *
 * <p>It exists so that the whole content path — encrypt, store, retrieve, verify — is exercisable
 * in a unit test with no external dependency. That property is worth protecting; a test suite
 * that needs a running Vault to check a round-trip stops being run.
 *
 * <p>The wrapping itself is honest AES-256-GCM, so the format and the failure modes match what a
 * real key manager produces.
 */
public final class LocalKeyManagementService implements KeyManagementService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int DATA_KEY_BYTES = 32;

    private final Map<String, byte[]> keyRings = new HashMap<>();
    private final Map<String, String> kekIds = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean available = true;

    /** Creates a key manager with the named rings, each holding a fresh random KEK. */
    public static LocalKeyManagementService withKeyRings(String... keyRingNames) {
        var kms = new LocalKeyManagementService();
        for (String name : keyRingNames) {
            kms.createKeyRing(name);
        }
        return kms;
    }

    /**
     * Rings whose keys are derived from a passphrase, so a restart can still read what the last
     * run wrote.
     *
     * <p>For development only, and a shade worse than the random version it replaces: the keys
     * are as strong as the passphrase and they survive in whatever holds it. That is the trade
     * being made, and it buys something real — with random keys, every restart silently turns
     * every stored credential and every stored document into ciphertext nobody can open, which
     * looks like a dozen unrelated bugs rather than one expected consequence.
     *
     * <p>Neither version belongs in production. A deployment replaces this bean with a KMS or an
     * HSM, where the key does not live in the same memory as the data it protects.
     */
    public static LocalKeyManagementService withDerivedKeyRings(String passphrase,
                                                                String... keyRingNames) {
        Objects.requireNonNull(passphrase, "passphrase");
        var kms = new LocalKeyManagementService();
        for (String name : keyRingNames) {
            kms.deriveKeyRing(passphrase, name);
        }
        return kms;
    }

    public void createKeyRing(String keyRing) {
        byte[] kek = new byte[DATA_KEY_BYTES];
        random.nextBytes(kek);
        keyRings.put(keyRing, kek);
        kekIds.put(keyRing, "local:" + keyRing + ":v1");
    }

    /**
     * The ring's key from a passphrase and the ring's own name.
     *
     * <p>The name is the salt, so two rings under one passphrase hold different keys — otherwise
     * "which ring was this sealed under" would stop being a question with an answer.
     */
    public void deriveKeyRing(String passphrase, String keyRing) {
        try {
            var spec = new javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(),
                    ("jvault:" + keyRing).getBytes(StandardCharsets.UTF_8),
                    210_000, DATA_KEY_BYTES * 8);
            byte[] kek = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            keyRings.put(keyRing, kek);
            kekIds.put(keyRing, "local-derived:" + keyRing + ":v1");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not derive a key ring", e);
        }
    }

    /**
     * Rotates the ring's key-encryption key. Existing wrapped data keys become unreadable until
     * they are re-wrapped, which is exactly the behaviour a real rotation has and exactly why
     * {@link #rewrap} exists.
     */
    public void rotateKeyRing(String keyRing, String newKekId) {
        requireRing(keyRing);
        byte[] kek = new byte[DATA_KEY_BYTES];
        random.nextBytes(kek);
        keyRings.put(keyRing, kek);
        kekIds.put(keyRing, newKekId);
    }

    /** Simulates an outage, so fail-closed behaviour can be tested. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public DataKey generateDataKey(String keyRing) {
        requireAvailable(keyRing);
        byte[] kek = requireRing(keyRing);

        byte[] dek = new byte[DATA_KEY_BYTES];
        random.nextBytes(dek);
        try {
            byte[] wrapped = wrap(kek, dek, keyRing);
            return new DataKey(kekIds.get(keyRing), dek, wrapped);
        } catch (GeneralSecurityException e) {
            throw new KmsUnavailableException(keyRing, "could not wrap a new data key", e);
        }
    }

    @Override
    public DataKey unwrap(String keyRing, String kekId, byte[] wrappedKey) {
        requireAvailable(keyRing);
        byte[] kek = requireRing(keyRing);
        try {
            byte[] dek = unwrapBytes(kek, wrappedKey, keyRing);
            return new DataKey(kekId, dek, wrappedKey);
        } catch (GeneralSecurityException e) {
            throw new KeyUnwrapException(
                    "wrapped data key is not valid under key ring '" + keyRing + "'", e);
        }
    }

    @Override
    public byte[] rewrap(String fromKeyRing, String toKeyRing, String kekId, byte[] wrappedKey) {
        requireAvailable(fromKeyRing);
        requireAvailable(toKeyRing);
        try {
            byte[] dek = unwrapBytes(requireRing(fromKeyRing), wrappedKey, fromKeyRing);
            try {
                return wrap(requireRing(toKeyRing), dek, toKeyRing);
            } finally {
                java.util.Arrays.fill(dek, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new KeyUnwrapException("could not re-wrap data key", e);
        }
    }

    @Override
    public String currentKekId(String keyRing) {
        requireRing(keyRing);
        return kekIds.get(keyRing);
    }

    // The key ring name is authenticated as associated data, so a data key wrapped for one ring
    // cannot be unwrapped under another even if both KEKs were somehow identical.
    private byte[] wrap(byte[] kek, byte[] dek, String keyRing) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, ALGORITHM),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(keyRing.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(dek);

        return ByteBuffer.allocate(nonce.length + ciphertext.length)
                .put(nonce).put(ciphertext).array();
    }

    private byte[] unwrapBytes(byte[] kek, byte[] wrapped, String keyRing)
            throws GeneralSecurityException {
        if (wrapped.length <= NONCE_BYTES) {
            throw new GeneralSecurityException("wrapped key is too short to be well-formed");
        }
        ByteBuffer buffer = ByteBuffer.wrap(wrapped);
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, ALGORITHM),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(keyRing.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(ciphertext);
    }

    private byte[] requireRing(String keyRing) {
        byte[] kek = keyRings.get(Objects.requireNonNull(keyRing, "keyRing"));
        if (kek == null) {
            throw new IllegalArgumentException("unknown key ring: " + keyRing);
        }
        return kek;
    }

    private void requireAvailable(String keyRing) {
        if (!available) {
            throw new KmsUnavailableException(keyRing, "simulated outage", null);
        }
    }
}
