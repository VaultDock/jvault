package dev.jvault.crypto.envelope;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * The self-describing prefix on every encrypted object.
 *
 * <pre>
 *   magic "JVLT" | format | alg | segment size | kekId | wrapped DEK
 * </pre>
 *
 * <p>It exists for disaster recovery. An object recovered without its database row is otherwise
 * an anonymous block of random bytes; with the header, an operator holding key-manager access can
 * identify which key-encryption key it belongs to and decrypt it (docs/09-encryption.md 9.8).
 * The same values are also stored in {@code content_version}, deliberately duplicated.
 *
 * <p><strong>The header is authenticated, without a separate MAC.</strong> Its hash is bound into
 * the associated data of every segment, so altering the segment size, the key id, or the wrapped
 * key makes decryption fail rather than succeed with the wrong parameters. That is stronger than
 * a separate header MAC would be: it uses the same key as the payload, so a header cannot be
 * lifted onto a different object.
 *
 * <p>Nothing here is secret. The wrapped data key is useless without the key manager, and the
 * key id names a key rather than containing one.
 */
public record EnvelopeHeader(int formatVersion,
                             Algorithm algorithm,
                             int segmentSize,
                             String kekId,
                             byte[] wrappedDataKey) {

    public static final byte[] MAGIC = {'J', 'V', 'L', 'T'};
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** Bounds that keep a corrupt or hostile header from causing a huge allocation. */
    private static final int MAX_KEK_ID_BYTES = 512;
    private static final int MAX_WRAPPED_KEY_BYTES = 4096;

    public enum Algorithm {
        /** Tink {@code AesGcmHkdfStreaming}, HMAC-SHA256 HKDF, 256-bit key. */
        AES256_GCM_HKDF_STREAMING(1);

        private final int id;

        Algorithm(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        static Algorithm byId(int id) throws EnvelopeFormatException {
            for (Algorithm a : values()) {
                if (a.id == id) {
                    return a;
                }
            }
            throw new EnvelopeFormatException("unknown algorithm id: " + id);
        }
    }

    public EnvelopeHeader {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(kekId, "kekId");
        Objects.requireNonNull(wrappedDataKey, "wrappedDataKey");
        wrappedDataKey = wrappedDataKey.clone();
        if (segmentSize <= 0) {
            throw new IllegalArgumentException("segmentSize must be positive");
        }
    }

    @Override
    public byte[] wrappedDataKey() {
        return wrappedDataKey.clone();
    }

    public byte[] serialize() {
        byte[] kekIdBytes = kekId.getBytes(StandardCharsets.UTF_8);
        if (kekIdBytes.length > MAX_KEK_ID_BYTES) {
            throw new IllegalArgumentException("kekId is too long");
        }
        if (wrappedDataKey.length > MAX_WRAPPED_KEY_BYTES) {
            throw new IllegalArgumentException("wrapped data key is too long");
        }
        return ByteBuffer.allocate(MAGIC.length + 1 + 1 + 4 + 2 + kekIdBytes.length
                        + 2 + wrappedDataKey.length)
                .put(MAGIC)
                .put((byte) formatVersion)
                .put((byte) algorithm.id())
                .putInt(segmentSize)
                .putShort((short) kekIdBytes.length)
                .put(kekIdBytes)
                .putShort((short) wrappedDataKey.length)
                .put(wrappedDataKey)
                .array();
    }

    /** Reads exactly one header, leaving the stream positioned at the first ciphertext byte. */
    public static Parsed parse(InputStream in) throws IOException {
        var counting = new CountingInputStream(in);
        var data = new DataInputStream(counting);

        byte[] magic = new byte[MAGIC.length];
        data.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new EnvelopeFormatException("not a jvault encrypted object");
        }

        int formatVersion = data.readUnsignedByte();
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new EnvelopeFormatException("unsupported envelope format version: " + formatVersion);
        }
        Algorithm algorithm = Algorithm.byId(data.readUnsignedByte());

        int segmentSize = data.readInt();
        if (segmentSize <= 0) {
            throw new EnvelopeFormatException("invalid segment size: " + segmentSize);
        }

        byte[] kekIdBytes = readLengthPrefixed(data, MAX_KEK_ID_BYTES, "kekId");
        byte[] wrappedKey = readLengthPrefixed(data, MAX_WRAPPED_KEY_BYTES, "wrapped data key");

        var header = new EnvelopeHeader(formatVersion, algorithm, segmentSize,
                new String(kekIdBytes, StandardCharsets.UTF_8), wrappedKey);
        return new Parsed(header, counting.count());
    }

    private static byte[] readLengthPrefixed(DataInputStream data, int max, String what)
            throws IOException {
        int length = data.readUnsignedShort();
        if (length == 0 || length > max) {
            throw new EnvelopeFormatException("invalid " + what + " length: " + length);
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return bytes;
    }

    /** SHA-256 over the serialized header, bound into every segment's associated data. */
    public byte[] hash() {
        return sha256(serialize());
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    /** @param length how many bytes the header occupied, so the payload offset is known */
    public record Parsed(EnvelopeHeader header, int length) {
    }

    /** Never prints the wrapped key, even though it is not secret — habit is the point. */
    @Override
    public String toString() {
        return "EnvelopeHeader[v" + formatVersion + ", " + algorithm + ", segment=" + segmentSize
                + ", kek=" + kekId + "]";
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private int count;

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        int count() {
            return count;
        }
    }
}
