package dev.jvault.crypto.envelope;

import dev.jvault.crypto.kms.KeyManagementService;
import dev.jvault.crypto.kms.KmsUnavailableException;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentCipherTest {

    private static final String KEY_RING = "sec-restricted";
    private static final int SEGMENT = 4096;   // small, so multi-segment cases stay quick

    private static final ObjectIdentity OBJECT =
            new ObjectIdentity("01J8ZQK5M3T4X9YV2A0B7CDEFG", "01J8ZQK7A1B2C3D4E5F6G7H8J9");

    private final LocalKeyManagementService kms =
            LocalKeyManagementService.withKeyRings(KEY_RING, "general");
    private final ContentCipher cipher = new ContentCipher(kms, SEGMENT);

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {0, 1, 100, 4095, 4096, 4097, 65536, 5_000_000})
        @DisplayName("plaintext survives encryption and decryption exactly")
        void roundTripsAtEverySegmentBoundary(int size) throws Exception {
            byte[] plaintext = randomBytes(size);

            byte[] ciphertext = encrypt(plaintext);
            byte[] recovered = decrypt(ciphertext);

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("the ciphertext does not contain the plaintext")
        void ciphertextRevealsNothing() throws Exception {
            byte[] plaintext = ("Credential material observed in the process environment: "
                    + "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                    .getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = encrypt(plaintext);

            assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1))
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .doesNotContain("wJalrXUtnFEMI");
        }

        @Test
        @DisplayName("encrypting the same plaintext twice produces different ciphertext")
        void encryptionIsNonDeterministic() throws Exception {
            byte[] plaintext = randomBytes(1000);

            // A fresh data key per object version, so identical content does not produce
            // identical bytes — otherwise a backend operator could tell which objects match.
            assertThat(encrypt(plaintext)).isNotEqualTo(encrypt(plaintext));
        }

        @Test
        @DisplayName("both digests are reported and are correct")
        void digestsAreCorrect() throws Exception {
            byte[] plaintext = randomBytes(10_000);
            var out = new ByteArrayOutputStream();

            ContentCipher.EncryptionResult result = cipher.encrypt(
                    OBJECT, KEY_RING, new ByteArrayInputStream(plaintext), out);

            assertThat(result.plaintextSize()).isEqualTo(10_000);
            assertThat(result.plaintextSha256()).isEqualTo(sha256(plaintext));
            assertThat(result.ciphertextSha256())
                    .as("covers the header too, so the sweeper can verify without a key")
                    .isEqualTo(sha256(out.toByteArray()));
        }

        @Test
        @DisplayName("the header is self-describing for disaster recovery")
        void headerIdentifiesTheKey() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(100));

            EnvelopeHeader.Parsed parsed =
                    EnvelopeHeader.parse(new ByteArrayInputStream(ciphertext));

            assertThat(parsed.header().kekId()).isEqualTo(kms.currentKekId(KEY_RING));
            assertThat(parsed.header().segmentSize()).isEqualTo(SEGMENT);
            assertThat(parsed.header().algorithm())
                    .isEqualTo(EnvelopeHeader.Algorithm.AES256_GCM_HKDF_STREAMING);
        }
    }

    @Nested
    @DisplayName("tamper detection")
    class Tampering {

        @Test
        @DisplayName("a single flipped bit anywhere in the payload fails decryption")
        void bitFlipInPayloadIsDetected() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(10_000));
            int header = headerLengthOf(ciphertext);

            for (int position : new int[]{header, header + 500, 5000, ciphertext.length - 1}) {
                byte[] corrupted = ciphertext.clone();
                corrupted[position] ^= 0x01;

                assertThatThrownBy(() -> decrypt(corrupted))
                        .as("flip at %d", position)
                        .isInstanceOf(IOException.class);
            }
        }

        @Test
        @DisplayName("a flipped bit in the header is detected too, by a different mechanism")
        void bitFlipInHeaderIsDetected() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(10_000));
            int header = headerLengthOf(ciphertext);

            // Two mechanisms cover the header, and which one fires depends on where the flip
            // lands. Corrupting the wrapped data key fails at unwrap, with a precise error.
            // Corrupting anything else changes the header hash bound into the associated data,
            // so every segment fails to authenticate. Either way nothing decrypts.
            for (int position = 0; position < header; position++) {
                byte[] corrupted = ciphertext.clone();
                corrupted[position] ^= 0x01;

                final int at = position;
                assertThatThrownBy(() -> decrypt(corrupted))
                        .as("flip at %d of %d header bytes", at, header)
                        .isInstanceOfAny(IOException.class,
                                KeyManagementService.KeyUnwrapException.class);
            }
        }

        @Test
        @DisplayName("corrupting the wrapped key fails at unwrap, and says so")
        void corruptWrappedKeyFailsAtUnwrap() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(1000));
            int header = headerLengthOf(ciphertext);

            // The wrapped key is the last field in the header.
            byte[] corrupted = ciphertext.clone();
            corrupted[header - 1] ^= 0x01;

            assertThatThrownBy(() -> decrypt(corrupted))
                    .isInstanceOf(KeyManagementService.KeyUnwrapException.class)
                    .hasMessageContaining("not valid under key ring");
        }

        @Test
        @DisplayName("truncating the object fails rather than returning a short read")
        void truncationIsDetected() throws Exception {
            // Three segments, so removing the last still leaves a structurally plausible object.
            byte[] ciphertext = encrypt(randomBytes(SEGMENT * 3));
            byte[] truncated = Arrays.copyOf(ciphertext, ciphertext.length - SEGMENT);

            assertThatThrownBy(() -> decrypt(truncated))
                    .as("a truncated object must never decrypt to a shorter plaintext")
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("swapping two segments is detected")
        void segmentReorderIsDetected() throws Exception {
            byte[] plaintext = randomBytes(SEGMENT * 3);
            byte[] ciphertext = encrypt(plaintext);

            int headerLength = headerLengthOf(ciphertext);
            int segmentCiphertext = SEGMENT;
            byte[] reordered = ciphertext.clone();

            // Tink binds the segment index into each nonce, so segments are not interchangeable.
            byte[] first = Arrays.copyOfRange(reordered, headerLength, headerLength + segmentCiphertext);
            byte[] second = Arrays.copyOfRange(reordered,
                    headerLength + segmentCiphertext, headerLength + 2 * segmentCiphertext);
            System.arraycopy(second, 0, reordered, headerLength, segmentCiphertext);
            System.arraycopy(first, 0, reordered, headerLength + segmentCiphertext, segmentCiphertext);

            assertThatThrownBy(() -> decrypt(reordered)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a segment from another object cannot be spliced in")
        void crossObjectSpliceIsDetected() throws Exception {
            byte[] ciphertextA = encrypt(randomBytes(SEGMENT * 2));

            var otherObject = new ObjectIdentity("01J8ZQOTHEROBJECTREF000000", OBJECT.versionId());
            var out = new ByteArrayOutputStream();
            cipher.encrypt(otherObject, KEY_RING,
                    new ByteArrayInputStream(randomBytes(SEGMENT * 2)), out);
            byte[] ciphertextB = out.toByteArray();

            int headerLength = headerLengthOf(ciphertextA);
            byte[] spliced = ciphertextA.clone();
            System.arraycopy(ciphertextB, headerLength, spliced, headerLength, SEGMENT);

            assertThatThrownBy(() -> decrypt(spliced)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("decrypting under the wrong object identity fails")
        void wrongIdentityFails() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(1000));
            var wrongObject = new ObjectIdentity(OBJECT.contentRef(), "01J8ZQDIFFERENTVERSION0000");

            assertThatThrownBy(() -> {
                try (InputStream in = cipher.decrypt(wrongObject, KEY_RING,
                        new ByteArrayInputStream(ciphertext))) {
                    in.readAllBytes();
                }
            }).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("altering the stored header is detected, without a separate header MAC")
        void headerTamperIsDetected() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(1000));

            // The segment size lives at a fixed offset: magic(4) + version(1) + alg(1).
            byte[] tampered = ciphertext.clone();
            tampered[6] ^= 0x01;

            // The header's hash is bound into every segment's associated data, so changing a
            // stored parameter makes decryption fail rather than proceed with wrong settings.
            assertThatThrownBy(() -> decrypt(tampered)).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("bytes that are not a jvault object are rejected clearly")
        void foreignBytesRejected() {
            byte[] notOurs = "this is just some text, not an encrypted object".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> decrypt(notOurs))
                    .isInstanceOf(EnvelopeFormatException.class)
                    .hasMessageContaining("not a jvault encrypted object");
        }
    }

    @Nested
    @DisplayName("key management")
    class Keys {

        @Test
        @DisplayName("encryption fails closed when the key manager is unavailable")
        void failsClosedOnKmsOutage() {
            kms.setAvailable(false);
            var out = new ByteArrayOutputStream();

            assertThatThrownBy(() -> cipher.encrypt(OBJECT, KEY_RING,
                    new ByteArrayInputStream(randomBytes(100)), out))
                    .isInstanceOf(KmsUnavailableException.class);

            assertThat(out.toByteArray())
                    .as("nothing is written, and certainly nothing unencrypted")
                    .isEmpty();
        }

        @Test
        @DisplayName("decryption fails when the key manager is unavailable")
        void decryptFailsOnKmsOutage() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(100));
            kms.setAvailable(false);

            assertThatThrownBy(() -> decrypt(ciphertext))
                    .isInstanceOf(KmsUnavailableException.class);
        }

        @Test
        @DisplayName("a data key wrapped for one ring cannot be unwrapped by another")
        void keyRingsAreIsolated() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(100));

            assertThatThrownBy(() -> {
                try (InputStream in = cipher.decrypt(OBJECT, "general",
                        new ByteArrayInputStream(ciphertext))) {
                    in.readAllBytes();
                }
            }).isInstanceOf(KeyManagementService.KeyUnwrapException.class);
        }

        @Test
        @DisplayName("after rotation the stale header key fails and the rewrapped key works")
        void kekRotationDoesNotRewriteContent() throws Exception {
            byte[] plaintext = randomBytes(20_000);
            var out = new ByteArrayOutputStream();
            ContentCipher.EncryptionResult result =
                    cipher.encrypt(OBJECT, KEY_RING, new ByteArrayInputStream(plaintext), out);
            byte[] storedObject = out.toByteArray();

            // What a rotation job does: rewrap each data key, update content_version, stop.
            // The objects themselves are never opened.
            byte[] rewrapped = kms.rewrap(KEY_RING, "general",
                    result.header().kekId(), result.header().wrappedDataKey());
            kms.rotateKeyRing(KEY_RING, "local:" + KEY_RING + ":v2");

            // The key recorded inside the object is now stale, and proves it by failing.
            assertThatThrownBy(() -> decrypt(storedObject))
                    .as("the header's wrapped key no longer opens under the rotated ring")
                    .isInstanceOf(KeyManagementService.KeyUnwrapException.class);

            // The rewrapped key from the database still opens the very same bytes.
            byte[] recovered;
            try (InputStream in = cipher.decrypt(OBJECT, "general",
                    new ByteArrayInputStream(storedObject),
                    KeyLocator.of(kms.currentKekId("general"), rewrapped))) {
                recovered = in.readAllBytes();
            }
            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("the current wrapped key from the database is used, not the stale one in the header")
        void databaseKeyBeatsHeaderKey() throws Exception {
            byte[] plaintext = randomBytes(5_000);
            var out = new ByteArrayOutputStream();
            ContentCipher.EncryptionResult result =
                    cipher.encrypt(OBJECT, KEY_RING, new ByteArrayInputStream(plaintext), out);
            byte[] storedObject = out.toByteArray();

            // Rewrap under a second ring, exactly as a key-ring migration would.
            byte[] wrappedUnderGeneral = kms.rewrap(KEY_RING, "general",
                    result.header().kekId(), result.header().wrappedDataKey());

            byte[] recovered;
            try (InputStream in = cipher.decrypt(OBJECT, "general",
                    new ByteArrayInputStream(storedObject),
                    KeyLocator.of(kms.currentKekId("general"), wrappedUnderGeneral))) {
                recovered = in.readAllBytes();
            }

            assertThat(recovered)
                    .as("rotation is a metadata operation; the object was never rewritten")
                    .isEqualTo(plaintext);
        }

        @Test
        @DisplayName("the data key is destroyed once the stream is closed")
        void dataKeyIsZeroedAfterUse() throws Exception {
            byte[] ciphertext = encrypt(randomBytes(1000));

            var seekable = cipher.openSeekable(OBJECT, KEY_RING,
                    new SeekableBytes(ciphertext));
            assertThat(seekable.dataKey().isDestroyed()).isFalse();

            seekable.close();

            assertThat(seekable.dataKey().isDestroyed()).isTrue();
        }
    }

    @Nested
    @DisplayName("ranged reads")
    class Ranged {

        @Test
        @DisplayName("a range in the middle of a multi-segment object reads correctly")
        void readsARangeWithoutDecryptingEverything() throws Exception {
            byte[] plaintext = randomBytes(SEGMENT * 4);
            byte[] ciphertext = encrypt(plaintext);

            byte[] slice = new byte[1000];
            try (var content = cipher.openSeekable(OBJECT, KEY_RING, new SeekableBytes(ciphertext))) {
                content.channel().position(SEGMENT * 2 + 17);
                var buffer = java.nio.ByteBuffer.wrap(slice);
                while (buffer.hasRemaining() && content.channel().read(buffer) > 0) {
                    // read until the slice is filled
                }
            }

            assertThat(slice).isEqualTo(
                    Arrays.copyOfRange(plaintext, SEGMENT * 2 + 17, SEGMENT * 2 + 17 + 1000));
        }

        @Test
        @DisplayName("the seekable view reports the plaintext size, not the ciphertext size")
        void seekableSizeIsPlaintextSize() throws Exception {
            byte[] plaintext = randomBytes(12_345);
            byte[] ciphertext = encrypt(plaintext);

            try (var content = cipher.openSeekable(OBJECT, KEY_RING, new SeekableBytes(ciphertext))) {
                assertThat(content.channel().size()).isEqualTo(12_345);
            }
        }
    }

    // --- helpers -----------------------------------------------------------------

    private byte[] encrypt(byte[] plaintext) throws IOException {
        var out = new ByteArrayOutputStream();
        cipher.encrypt(OBJECT, KEY_RING, new ByteArrayInputStream(plaintext), out);
        return out.toByteArray();
    }

    private byte[] decrypt(byte[] ciphertext) throws IOException {
        try (InputStream in = cipher.decrypt(OBJECT, KEY_RING, new ByteArrayInputStream(ciphertext))) {
            return in.readAllBytes();
        }
    }

    private static int headerLengthOf(byte[] ciphertext) throws IOException {
        return EnvelopeHeader.parse(new ByteArrayInputStream(ciphertext)).length();
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size * 31L).nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    /** An in-memory {@link java.nio.channels.SeekableByteChannel}, for the ranged-read tests. */
    private static final class SeekableBytes implements java.nio.channels.SeekableByteChannel {
        private final byte[] bytes;
        private long position;
        private boolean open = true;

        SeekableBytes(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(java.nio.ByteBuffer dst) {
            if (position >= bytes.length) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), bytes.length - position);
            dst.put(bytes, (int) position, n);
            position += n;
            return n;
        }

        @Override
        public int write(java.nio.ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public java.nio.channels.SeekableByteChannel position(long newPosition) {
            this.position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public java.nio.channels.SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
