package dev.jvault.storage.filesystem;

import dev.jvault.crypto.envelope.ContentCipher;
import dev.jvault.crypto.envelope.ObjectIdentity;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import dev.jvault.storage.spi.ContentStore;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.StoredObjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two layers composed: encrypt, then store. This is where the property that actually matters
 * is observable — what someone holding only the storage credential can learn.
 */
class EncryptedStorageIntegrationTest {

    private static final String KEY_RING = "sec-restricted";
    private static final String CONTENT_REF = "01J8ZQK5M3T4X9YV2A0B7CDEFG";
    private static final String VERSION_ID = "01J8ZQK7A1B2C3D4E5F6G7H8J9";

    private static final String SENSITIVE = """
            Host build-agent-07 opened a TLS connection to 203.0.113.44:443 at 09:41Z.
            Process: /opt/ci/runner (pid 21884).
            Credential material observed in the process environment:
            AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
            """;

    @TempDir
    Path root;

    private FilesystemContentStore store;
    private ContentCipher cipher;
    private LocalKeyManagementService kms;
    private ObjectKey key;
    private ObjectIdentity identity;

    @BeforeEach
    void setUp() {
        store = new FilesystemContentStore("fs-local", root);
        kms = LocalKeyManagementService.withKeyRings(KEY_RING);
        cipher = new ContentCipher(kms, 4096);
        key = new ObjectKey("acme", CONTENT_REF, VERSION_ID);
        identity = new ObjectIdentity(CONTENT_REF, VERSION_ID);
    }

    @Test
    @DisplayName("content round-trips through encryption and the store")
    void roundTrip() throws Exception {
        StoredObjectRef ref = storeEncrypted(SENSITIVE.getBytes(StandardCharsets.UTF_8));

        byte[] recovered;
        try (InputStream in = cipher.decrypt(identity, KEY_RING, store.open(ref))) {
            recovered = in.readAllBytes();
        }

        assertThat(new String(recovered, StandardCharsets.UTF_8)).isEqualTo(SENSITIVE);
    }

    @Test
    @DisplayName("someone holding only the storage credential learns nothing")
    void bytesOnDiskRevealNothing() throws Exception {
        storeEncrypted(SENSITIVE.getBytes(StandardCharsets.UTF_8));

        Path onDisk = store.pathFor(key);
        byte[] raw = Files.readAllBytes(onDisk);
        String asText = new String(raw, StandardCharsets.ISO_8859_1);

        // This is the threat the whole design is built around: a compromised storage account,
        // an over-permissioned bucket policy, a mis-scoped role, a curious administrator.
        assertThat(asText)
                .doesNotContain("AWS_SECRET_ACCESS_KEY")
                .doesNotContain("wJalrXUtnFEMI")
                .doesNotContain("build-agent-07")
                .doesNotContain("203.0.113.44");

        // The path gives nothing away either.
        assertThat(onDisk.toString()).doesNotContain("incident").doesNotContain("pcap");

        // Only the format marker is recognisable, which is deliberate: it is what makes an
        // object recovered without its database row identifiable at all.
        assertThat(asText).startsWith("JVLT");
    }

    @Test
    @DisplayName("tampering with the stored file is detected on read")
    void tamperingOnDiskIsDetected() throws Exception {
        StoredObjectRef ref = storeEncrypted(SENSITIVE.getBytes(StandardCharsets.UTF_8));

        // Someone with write access to the volume flips a byte in the payload.
        Path onDisk = store.pathFor(key);
        byte[] raw = Files.readAllBytes(onDisk);
        raw[raw.length - 20] ^= 0x01;
        Files.write(onDisk, raw);

        assertThatThrownBy(() -> {
            try (InputStream in = cipher.decrypt(identity, KEY_RING, store.open(ref))) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("one object's bytes cannot be swapped for another's")
    void objectSubstitutionIsDetected() throws Exception {
        StoredObjectRef target = storeEncrypted(SENSITIVE.getBytes(StandardCharsets.UTF_8));

        // A second object, encrypted under the same key ring.
        var otherKey = new ObjectKey("acme", "01J8ZQOTHERCONTENTREF00000", VERSION_ID);
        var otherIdentity = new ObjectIdentity(otherKey.contentRef(), VERSION_ID);
        var out = new ByteArrayOutputStream();
        cipher.encrypt(otherIdentity, KEY_RING,
                new ByteArrayInputStream("something harmless".getBytes(StandardCharsets.UTF_8)), out);

        // Overwrite the first object's file with the second object's bytes.
        Files.write(store.pathFor(key), out.toByteArray());

        // Segments are bound to their object's identity, so the swap does not authenticate even
        // though both objects are validly encrypted under the same ring.
        assertThatThrownBy(() -> {
            try (InputStream in = cipher.decrypt(identity, KEY_RING, store.open(target))) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a ranged read decrypts only what it needs")
    void rangedReadThroughTheStore() throws Exception {
        byte[] content = new byte[40_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        StoredObjectRef ref = storeEncrypted(content);

        byte[] slice = new byte[500];
        try (var channel = store.openChannel(ref);
             var decrypting = cipher.openSeekable(identity, KEY_RING, channel)) {
            decrypting.channel().position(12_345);
            var buffer = java.nio.ByteBuffer.wrap(slice);
            while (buffer.hasRemaining() && decrypting.channel().read(buffer) > 0) {
                // fill the slice
            }
        }

        assertThat(slice).isEqualTo(Arrays.copyOfRange(content, 12_345, 12_845));
    }

    @Test
    @DisplayName("the ciphertext digest verifies stored bytes without touching the key manager")
    void integritySweepNeedsNoKey() throws Exception {
        var encrypted = new ByteArrayOutputStream();
        ContentCipher.EncryptionResult result = cipher.encrypt(identity, KEY_RING,
                new ByteArrayInputStream(SENSITIVE.getBytes(StandardCharsets.UTF_8)), encrypted);
        store.put(ContentStore.PutRequest.of(key,
                new ByteArrayInputStream(encrypted.toByteArray()), encrypted.size()));

        // Sweeping millions of objects through the key manager would be neither fast nor cheap,
        // so integrity verification uses the ciphertext digest and no key material at all.
        byte[] onDisk = Files.readAllBytes(store.pathFor(key));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(onDisk);

        assertThat(digest).isEqualTo(result.ciphertextSha256());
    }

    private StoredObjectRef storeEncrypted(byte[] plaintext) throws IOException {
        var encrypted = new ByteArrayOutputStream();
        cipher.encrypt(identity, KEY_RING, new ByteArrayInputStream(plaintext), encrypted);
        return store.put(ContentStore.PutRequest.of(key,
                new ByteArrayInputStream(encrypted.toByteArray()), encrypted.size()));
    }
}
