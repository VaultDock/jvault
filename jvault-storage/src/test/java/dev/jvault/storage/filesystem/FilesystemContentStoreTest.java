package dev.jvault.storage.filesystem;

import dev.jvault.storage.spi.ContentStore;
import dev.jvault.storage.spi.DeleteMode;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.StorageException;
import dev.jvault.storage.spi.StoredObjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemContentStoreTest {

    private static final String TENANT = "acme";
    private static final String CONTENT_REF = "01J8ZQK5M3T4X9YV2A0B7CDEFG";
    private static final String VERSION_ID = "01J8ZQK7A1B2C3D4E5F6G7H8J9";

    @TempDir
    Path root;

    private FilesystemContentStore store;
    private ObjectKey key;

    @BeforeEach
    void setUp() {
        store = new FilesystemContentStore("fs-local", root);
        key = new ObjectKey(TENANT, CONTENT_REF, VERSION_ID);
    }

    @Nested
    @DisplayName("object keys reveal nothing")
    class Keys {

        @Test
        @DisplayName("a key carrying a filename is rejected at construction")
        void filenamesAreRejected() {
            // An attachment named 2026-Q3-layoffs-final.xlsx is often the most sensitive thing
            // about the file, and storage keys are visible to anyone with listing rights.
            assertThatThrownBy(() -> new ObjectKey(TENANT, "2026-Q3-layoffs-final.xlsx", VERSION_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must never carry filenames");
        }

        @Test
        @DisplayName("keys with paths, spaces or dots are rejected")
        void nonOpaqueComponentsAreRejected() {
            for (String bad : List.of("../escape", "has space", "has.dot", "has/slash", "")) {
                assertThatThrownBy(() -> new ObjectKey(TENANT, bad, VERSION_ID))
                        .as("component %s", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("the on-disk path contains nothing but opaque identifiers")
        void pathLeaksNothing() {
            String path = store.pathFor(key).toString();

            assertThat(path)
                    .contains(CONTENT_REF)
                    .contains(VERSION_ID)
                    .doesNotContain(".xlsx")
                    .doesNotContain("layoffs");
        }

        @Test
        @DisplayName("objects fan out across directories rather than piling into one")
        void pathsFanOut() {
            Path path = store.pathFor(key);

            // A single directory with a million entries is slow everywhere and unusable on some
            // filesystems, so the first two byte-pairs of the reference become directories.
            assertThat(root.relativize(path).toString())
                    .startsWith(TENANT + "/" + CONTENT_REF.substring(0, 2)
                            + "/" + CONTENT_REF.substring(2, 4));
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        @DisplayName("a stored object reads back byte for byte")
        void roundTrip() throws Exception {
            byte[] content = randomBytes(100_000);

            StoredObjectRef ref = put(content);

            assertThat(read(ref)).isEqualTo(content);
            assertThat(ref.backendName()).isEqualTo("fs-local");
        }

        @Test
        @DisplayName("an empty object is still an object")
        void emptyObject() throws Exception {
            StoredObjectRef ref = put(new byte[0]);

            assertThat(read(ref)).isEmpty();
            assertThat(store.stat(ref)).isPresent();
        }

        @Test
        @DisplayName("writing the same key twice with the same bytes is safe")
        void putIsRepeatable() throws Exception {
            byte[] content = randomBytes(5000);

            put(content);
            StoredObjectRef ref = put(content);

            // Content-first ordering means a retry after an ambiguous failure re-writes the same
            // key; it must overwrite rather than accumulate.
            assertThat(read(ref)).isEqualTo(content);
            assertThat(objectFileCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("no partial object is left behind when the source fails mid-stream")
        void failedWriteLeavesNoObject() {
            InputStream failing = new InputStream() {
                private int remaining = 1000;

                @Override
                public int read() throws IOException {
                    if (remaining-- <= 0) {
                        throw new IOException("source failed");
                    }
                    return 'x';
                }
            };

            assertThatThrownBy(() -> store.put(ContentStore.PutRequest.of(key, failing, -1)))
                    .isInstanceOf(StorageException.class);

            // A truncated object that a later read accepts is worse than a failed write,
            // because the failure is silent and surfaces much later.
            assertThat(store.stat(new StoredObjectRef("fs-local", key))).isEmpty();
            assertThat(objectFileCount()).isZero();
        }

        @Test
        @DisplayName("a declared content length that does not match is rejected")
        void contentLengthMismatchIsRejected() {
            byte[] content = randomBytes(100);

            assertThatThrownBy(() -> store.put(new ContentStore.PutRequest(
                    key, new ByteArrayInputStream(content), 999, "application/octet-stream")))
                    .isInstanceOf(StorageException.class)
                    .satisfies(e -> assertThat(((StorageException) e).code())
                            .isEqualTo("CONTENT_LENGTH_MISMATCH"));

            assertThat(objectFileCount()).isZero();
        }
    }

    @Nested
    @DisplayName("reading and deleting")
    class ReadingAndDeleting {

        @Test
        @DisplayName("reading a missing object reports it clearly")
        void missingObject() {
            var ref = new StoredObjectRef("fs-local", key);

            assertThatThrownBy(() -> store.open(ref))
                    .isInstanceOf(StorageException.class)
                    .satisfies(e -> assertThat(((StorageException) e).code())
                            .isEqualTo("OBJECT_NOT_FOUND"));
        }

        @Test
        @DisplayName("a soft delete is reversible")
        void softDeleteAndRestore() throws Exception {
            byte[] content = randomBytes(2000);
            StoredObjectRef ref = put(content);

            store.delete(ref, DeleteMode.SOFT);
            assertThat(store.stat(ref)).isEmpty();

            store.restore(ref);
            assertThat(read(ref)).isEqualTo(content);
        }

        @Test
        @DisplayName("a hard delete is not")
        void hardDeleteIsPermanent() throws Exception {
            StoredObjectRef ref = put(randomBytes(2000));

            store.delete(ref, DeleteMode.HARD);

            assertThat(store.stat(ref)).isEmpty();
            assertThatThrownBy(() -> store.restore(ref)).isInstanceOf(StorageException.class);
        }

        @Test
        @DisplayName("deleting something that is already gone is not an error")
        void deleteIsIdempotent() {
            var ref = new StoredObjectRef("fs-local", key);

            store.delete(ref, DeleteMode.HARD);
            store.delete(ref, DeleteMode.SOFT);
        }

        @Test
        @DisplayName("stat reports size and modification time")
        void statReportsSize() throws Exception {
            StoredObjectRef ref = put(randomBytes(4321));

            assertThat(store.stat(ref)).isPresent().hasValueSatisfying(stat -> {
                assertThat(stat.sizeBytes()).isEqualTo(4321);
                assertThat(stat.lastModified()).isNotNull();
            });
        }

        @Test
        @DisplayName("a seekable channel supports ranged reads")
        void seekableChannel() throws Exception {
            byte[] content = randomBytes(10_000);
            StoredObjectRef ref = put(content);

            byte[] slice = new byte[100];
            try (var channel = store.openChannel(ref)) {
                channel.position(5000);
                channel.read(java.nio.ByteBuffer.wrap(slice));
            }

            assertThat(slice).isEqualTo(java.util.Arrays.copyOfRange(content, 5000, 5100));
        }
    }

    @Nested
    @DisplayName("capabilities and health")
    class CapabilitiesAndHealth {

        @Test
        @DisplayName("the backend does not claim capabilities it cannot enforce")
        void doesNotOverclaim() {
            var capabilities = store.capabilities();

            assertThat(capabilities.immutabilityLock())
                    .as("we will not claim WORM on a filesystem")
                    .isFalse();
            assertThat(capabilities.serverSideEncryption())
                    .as("on this backend, application-level encryption is the only layer")
                    .isFalse();
            assertThat(capabilities.nativeVersioning()).isFalse();
            assertThat(capabilities.rangeReads()).isTrue();
            assertThat(capabilities.softDelete()).isTrue();
        }

        @Test
        @DisplayName("health reports a usable root")
        void healthUp() {
            assertThat(store.health().healthy()).isTrue();
        }

        @Test
        @DisplayName("health reports an unusable root rather than failing later")
        void healthDown() throws Exception {
            Path blocked = root.resolve("blocked");
            Files.createFile(blocked);   // a file where a directory is needed

            var broken = new FilesystemContentStore("fs-broken", blocked);

            assertThat(broken.health().healthy()).isFalse();
        }
    }

    // --- helpers -----------------------------------------------------------------

    private StoredObjectRef put(byte[] content) {
        return store.put(ContentStore.PutRequest.of(
                key, new ByteArrayInputStream(content), content.length));
    }

    private byte[] read(StoredObjectRef ref) throws IOException {
        try (InputStream in = store.open(ref)) {
            return in.readAllBytes();
        }
    }

    /** Counts real objects, ignoring any temp files a failed write might have left. */
    private long objectFileCount() {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".partial"))
                    .count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size * 17L).nextBytes(bytes);
        return bytes;
    }

    @Test
    @DisplayName("the store never sees plaintext — it is handed opaque bytes and a generic type")
    void mediaTypeDefaultsToOpaque() {
        var request = ContentStore.PutRequest.of(key, new ByteArrayInputStream(new byte[0]), 0);

        // A backend that knows it holds a PDF has learned something about the content.
        assertThat(request.mediaType()).isEqualTo("application/octet-stream");
    }
}
