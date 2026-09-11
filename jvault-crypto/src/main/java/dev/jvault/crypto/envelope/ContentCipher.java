package dev.jvault.crypto.envelope;

import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import dev.jvault.crypto.kms.DataKey;
import dev.jvault.crypto.kms.KeyManagementService;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Envelope encryption for content streams (docs/09-encryption.md 9.3).
 *
 * <p>Per object version: the key manager mints a data key, the content is encrypted under it in
 * authenticated segments, and only the <em>wrapped</em> key is stored. A reader therefore needs
 * both the storage backend and the key manager, which are deliberately different trust domains.
 *
 * <p><strong>The segment construction is Tink's.</strong> {@code AesGcmHkdfStreaming} derives a
 * per-segment key by HKDF, and encodes the segment index and a final-segment flag into each
 * nonce. That is what makes segments impossible to reorder, duplicate or truncate undetectably —
 * the three attacks that naive per-chunk GCM is wide open to. We supply the object identity as
 * associated data on top, so a segment cannot be lifted from one object into another.
 *
 * <p>Segments also make streaming honest: a single GCM ciphertext cannot be authenticated until
 * its end, so decrypting a 1 GB object would mean buffering all of it before releasing any — or
 * emitting unverified plaintext and hoping. Segments let us authenticate and release a megabyte
 * at a time, and they are what makes {@link #openSeekable} possible for range requests.
 */
public final class ContentCipher {

    /** 1 MiB, as designed. Small enough to stream, large enough that overhead is negligible. */
    public static final int DEFAULT_SEGMENT_SIZE = 1024 * 1024;

    private static final String HKDF_ALGORITHM = "HmacSha256";
    private static final int KEY_SIZE_BYTES = 32;
    private static final int NO_OFFSET = 0;

    private final KeyManagementService kms;
    private final int segmentSize;

    public ContentCipher(KeyManagementService kms) {
        this(kms, DEFAULT_SEGMENT_SIZE);
    }

    public ContentCipher(KeyManagementService kms, int segmentSize) {
        this.kms = Objects.requireNonNull(kms, "kms");
        if (segmentSize < 1024) {
            throw new IllegalArgumentException("segment size must be at least 1024 bytes");
        }
        this.segmentSize = segmentSize;
    }

    /**
     * Encrypts {@code plaintext} into {@code out}, writing the envelope header first.
     *
     * <p>Both digests are computed in one pass: the plaintext digest is the authoritative content
     * identity, and the ciphertext digest lets the integrity sweeper verify stored objects without
     * decrypting them — which matters, because sweeping millions of objects through the key
     * manager would be neither fast nor cheap (docs/08-storage.md 8.5).
     *
     * <p>The stream is fully consumed but not closed; the caller owns both streams.
     */
    public EncryptionResult encrypt(ObjectIdentity identity,
                                    String keyRing,
                                    InputStream plaintext,
                                    OutputStream out) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(out, "out");

        try (DataKey dataKey = kms.generateDataKey(keyRing)) {
            var header = new EnvelopeHeader(EnvelopeHeader.CURRENT_FORMAT_VERSION,
                    EnvelopeHeader.Algorithm.AES256_GCM_HKDF_STREAMING,
                    segmentSize, dataKey.kekId(), dataKey.wrapped());
            byte[] headerBytes = header.serialize();

            MessageDigest plaintextDigest = sha256();
            MessageDigest ciphertextDigest = sha256();

            // The ciphertext digest covers the header too, so tampering with stored parameters
            // is visible to the integrity sweeper without any key material.
            var digestingOut = new DigestOutputStream(out, ciphertextDigest);
            digestingOut.write(headerBytes);

            byte[] aad = associatedData(identity, EnvelopeHeader.sha256(headerBytes));

            long plaintextSize;
            // Closing the encrypting stream is what flushes and tags the final segment, so it
            // must happen before the digests are read — and it must not close the caller's
            // OutputStream, hence the shield.
            try {
                StreamingAead aead = streamingAead(dataKey.plaintext(), segmentSize);
                try (OutputStream encrypting = aead.newEncryptingStream(
                        new NonClosingOutputStream(digestingOut), aad);
                     DigestInputStream digestingIn =
                             new DigestInputStream(plaintext, plaintextDigest)) {
                    plaintextSize = digestingIn.transferTo(encrypting);
                }
            } catch (GeneralSecurityException e) {
                throw new ContentCipherException("could not start encryption", e);
            }
            digestingOut.flush();

            return new EncryptionResult(header, plaintextSize, plaintextDigest.digest(),
                    ciphertextDigest.digest(), headerBytes.length);
        }
    }

    /**
     * Opens a decrypting stream over a full encrypted object.
     *
     * <p>The returned stream owns the data key and zeroes it on close, so the key lives exactly
     * as long as the read. Closing it also closes {@code ciphertext}.
     *
     * @throws EnvelopeFormatException if the bytes are not a jvault object
     */
    public InputStream decrypt(ObjectIdentity identity, String keyRing, InputStream ciphertext)
            throws IOException {
        return decrypt(identity, keyRing, ciphertext, KeyLocator.fromHeader());
    }

    /**
     * Opens a decrypting stream, taking the wrapped data key from {@code keyLocator}.
     *
     * <p>Normal reads pass the key from {@code content_version}, which stays current across KEK
     * rotations; {@link KeyLocator#fromHeader()} is the disaster-recovery path. See
     * {@link KeyLocator}.
     */
    public InputStream decrypt(ObjectIdentity identity,
                               String keyRing,
                               InputStream ciphertext,
                               KeyLocator keyLocator) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(keyLocator, "keyLocator");

        EnvelopeHeader.Parsed parsed = EnvelopeHeader.parse(ciphertext);
        EnvelopeHeader header = parsed.header();

        KeyLocator.WrappedKey wrappedKey = keyLocator.locate(header);
        DataKey dataKey = kms.unwrap(keyRing, wrappedKey.kekId(), wrappedKey.bytes());
        try {
            byte[] aad = associatedData(identity, header.hash());
            StreamingAead aead = streamingAead(dataKey.plaintext(), header.segmentSize());
            InputStream decrypting = aead.newDecryptingStream(ciphertext, aad);
            return new KeyOwningInputStream(decrypting, dataKey);
        } catch (GeneralSecurityException e) {
            dataKey.close();
            throw new ContentCipherException("could not start decryption", e);
        } catch (RuntimeException | IOException e) {
            dataKey.close();
            throw e;
        }
    }

    /**
     * Opens a seekable view for range requests, decrypting only the segments a range touches.
     *
     * <p>This is what makes an authenticated 1 GB object usable for a browser range request. Note
     * what it costs: a ranged read verifies the segments it reads, but cannot verify the
     * whole-object plaintext digest, so the API that exposes it must say so
     * (docs/08-storage.md 8.5).
     */
    public SeekableDecryptingContent openSeekable(ObjectIdentity identity,
                                                  String keyRing,
                                                  java.nio.channels.SeekableByteChannel ciphertext)
            throws IOException {
        return openSeekable(identity, keyRing, ciphertext, KeyLocator.fromHeader());
    }

    public SeekableDecryptingContent openSeekable(ObjectIdentity identity,
                                                  String keyRing,
                                                  java.nio.channels.SeekableByteChannel ciphertext,
                                                  KeyLocator keyLocator) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(keyLocator, "keyLocator");

        EnvelopeHeader.Parsed parsed = readHeader(ciphertext);
        EnvelopeHeader header = parsed.header();

        KeyLocator.WrappedKey wrappedKey = keyLocator.locate(header);
        DataKey dataKey = kms.unwrap(keyRing, wrappedKey.kekId(), wrappedKey.bytes());
        try {
            byte[] aad = associatedData(identity, header.hash());
            StreamingAead aead = streamingAead(dataKey.plaintext(), header.segmentSize());
            var payload = new OffsetSeekableByteChannel(ciphertext, parsed.length());
            return new SeekableDecryptingContent(
                    aead.newSeekableDecryptingChannel(payload, aad), dataKey, header);
        } catch (GeneralSecurityException e) {
            dataKey.close();
            throw new ContentCipherException("could not open a seekable decrypting channel", e);
        } catch (RuntimeException | IOException e) {
            dataKey.close();
            throw e;
        }
    }

    private static EnvelopeHeader.Parsed readHeader(java.nio.channels.SeekableByteChannel channel)
            throws IOException {
        channel.position(0);
        var buffer = new ByteArrayOutputStream();
        var chunk = java.nio.ByteBuffer.allocate(512);
        int read = channel.read(chunk);
        if (read > 0) {
            buffer.write(chunk.array(), 0, read);
        }
        return EnvelopeHeader.parse(new java.io.ByteArrayInputStream(buffer.toByteArray()));
    }

    /**
     * Binds the ciphertext to this exact object and these exact header parameters.
     *
     * <p>Without it, an attacker with write access to the store could swap one object's bytes for
     * another's and every segment would still authenticate — the crypto would be intact and the
     * system would be lying.
     */
    static byte[] associatedData(ObjectIdentity identity, byte[] headerHash) {
        byte[] ref = identity.contentRef().getBytes(StandardCharsets.UTF_8);
        byte[] version = identity.versionId().getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[ref.length + 1 + version.length + 1 + headerHash.length];

        int at = 0;
        System.arraycopy(ref, 0, aad, at, ref.length);
        at += ref.length;
        aad[at++] = 0;
        System.arraycopy(version, 0, aad, at, version.length);
        at += version.length;
        aad[at++] = 0;
        System.arraycopy(headerHash, 0, aad, at, headerHash.length);
        return aad;
    }

    private static StreamingAead streamingAead(byte[] dataKey, int segmentSize)
            throws GeneralSecurityException {
        return new AesGcmHkdfStreaming(dataKey, HKDF_ALGORITHM, KEY_SIZE_BYTES, segmentSize, NO_OFFSET);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    /** What the caller must persist in {@code content_version} for the object to be readable. */
    public record EncryptionResult(EnvelopeHeader header,
                                   long plaintextSize,
                                   byte[] plaintextSha256,
                                   byte[] ciphertextSha256,
                                   int headerLength) {

        public EncryptionResult {
            plaintextSha256 = plaintextSha256.clone();
            ciphertextSha256 = ciphertextSha256.clone();
        }

        @Override
        public byte[] plaintextSha256() {
            return plaintextSha256.clone();
        }

        @Override
        public byte[] ciphertextSha256() {
            return ciphertextSha256.clone();
        }
    }

    /** A decrypting channel that owns its data key and zeroes it on close. */
    public record SeekableDecryptingContent(java.nio.channels.SeekableByteChannel channel,
                                            DataKey dataKey,
                                            EnvelopeHeader header) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                dataKey.close();
            }
        }
    }

    public static class ContentCipherException extends IOException {
        public ContentCipherException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class KeyOwningInputStream extends FilterInputStream {
        private final DataKey dataKey;

        KeyOwningInputStream(InputStream in, DataKey dataKey) {
            super(in);
            this.dataKey = dataKey;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                dataKey.close();
            }
        }
    }

    /** Lets Tink close its encrypting stream without closing the caller's output. */
    private static final class NonClosingOutputStream extends java.io.FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
