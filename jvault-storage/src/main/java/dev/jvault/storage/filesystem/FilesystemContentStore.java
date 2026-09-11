package dev.jvault.storage.filesystem;

import dev.jvault.storage.spi.Capabilities;
import dev.jvault.storage.spi.ContentStore;
import dev.jvault.storage.spi.DeleteMode;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.ObjectStat;
import dev.jvault.storage.spi.StorageException;
import dev.jvault.storage.spi.StoredObjectRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;

/**
 * Filesystem backend.
 *
 * <p>Under decision D2 this is a <strong>production</strong> backend, not a development
 * convenience, and its constraints are load-bearing rather than incidental:
 *
 * <ul>
 *   <li><strong>Writes are atomic.</strong> Content goes to a temporary file in the same
 *       directory and is then moved into place with {@code ATOMIC_MOVE}, so a crash mid-write
 *       leaves a stray temp file rather than a truncated object that a later read would
 *       cheerfully accept.</li>
 *   <li><strong>The volume must be POSIX-consistent across nodes.</strong> This is the single
 *       largest operational risk D2 introduces. NFS locking semantics have caused more
 *       data-integrity incidents in this category than almost anything else, which is why
 *       docs/08-storage.md 8.3 recommends S3-compatible object storage ahead of a shared
 *       filesystem wherever it is available.</li>
 *   <li><strong>No server-side encryption underneath.</strong> On this backend, application-level
 *       encryption is the only confidentiality layer there is.</li>
 * </ul>
 *
 * <p>Paths fan out on the first two byte-pairs of the content reference, because a single
 * directory holding a million entries is slow on every filesystem and unusable on some.
 */
public final class FilesystemContentStore implements ContentStore {

    private static final String TEMP_SUFFIX = ".partial";
    private static final String TRASH_DIR = ".trash";

    private final String name;
    private final Path root;

    public FilesystemContentStore(String name, Path root) {
        this.name = Objects.requireNonNull(name, "name");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(
                false,  // nativeVersioning  — jvault versions by writing a new key
                false,  // serverSideEncryption — volume encryption is below our visibility
                false,  // immutabilityLock  — we will not claim WORM we cannot enforce
                true,   // rangeReads
                true,   // conditionalWrites — an atomic move gives create-if-absent
                false,  // checksumOnWrite   — no independent verification by the platform
                true,   // softDelete        — implemented here, not by the filesystem
                Long.MAX_VALUE);
    }

    @Override
    public StoredObjectRef put(PutRequest request) {
        Path target = pathFor(request.key());
        Path temp = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + TEMP_SUFFIX);

        try {
            Files.createDirectories(target.getParent());

            long written;
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                written = request.content().transferTo(out);
            }

            if (request.contentLength() >= 0 && written != request.contentLength()) {
                Files.deleteIfExists(temp);
                throw new StorageException("CONTENT_LENGTH_MISMATCH",
                        "expected " + request.contentLength() + " bytes, wrote " + written);
            }

            moveIntoPlace(temp, target);
            return new StoredObjectRef(name, request.key());

        } catch (IOException e) {
            deleteQuietly(temp);
            throw new StorageException("WRITE_FAILED", "could not write " + request.key(), e);
        }
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network filesystems refuse atomic moves. Falling back keeps the store usable,
            // but the window where a reader can see a partial object is real, so it is worth
            // surfacing rather than silently accepting.
            throw new StorageException("ATOMIC_MOVE_UNSUPPORTED",
                    "the filesystem at " + root + " does not support atomic moves, so writes "
                            + "cannot be made crash-safe; use a different volume or backend", e);
        }
    }

    @Override
    public InputStream open(StoredObjectRef ref) {
        Path path = pathFor(ref.key());
        if (!Files.exists(path)) {
            throw StorageException.notFound(ref);
        }
        try {
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new StorageException("READ_FAILED", "could not read " + ref.key(), e);
        }
    }

    @Override
    public SeekableByteChannel openChannel(StoredObjectRef ref) {
        Path path = pathFor(ref.key());
        if (!Files.exists(path)) {
            throw StorageException.notFound(ref);
        }
        try {
            return Files.newByteChannel(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new StorageException("READ_FAILED", "could not open " + ref.key(), e);
        }
    }

    @Override
    public void delete(StoredObjectRef ref, DeleteMode mode) {
        Path path = pathFor(ref.key());
        if (!Files.exists(path)) {
            return;   // Deletion is idempotent: the desired end state already holds.
        }
        try {
            if (mode == DeleteMode.HARD) {
                Files.delete(path);
            } else {
                Path trashed = trashPathFor(ref.key());
                Files.createDirectories(trashed.getParent());
                Files.move(path, trashed, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("DELETE_FAILED", "could not delete " + ref.key(), e);
        }
    }

    @Override
    public void restore(StoredObjectRef ref) {
        Path trashed = trashPathFor(ref.key());
        if (!Files.exists(trashed)) {
            throw StorageException.notFound(ref);
        }
        Path target = pathFor(ref.key());
        try {
            Files.createDirectories(target.getParent());
            Files.move(trashed, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("RESTORE_FAILED", "could not restore " + ref.key(), e);
        }
    }

    @Override
    public Optional<ObjectStat> stat(StoredObjectRef ref) {
        Path path = pathFor(ref.key());
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return Optional.of(new ObjectStat(attributes.size(),
                    attributes.lastModifiedTime().toInstant(), null));
        } catch (IOException e) {
            throw new StorageException("STAT_FAILED", "could not stat " + ref.key(), e);
        }
    }

    @Override
    public Health health() {
        try {
            Files.createDirectories(root);
            if (!Files.isWritable(root)) {
                return Health.down("root is not writable: " + root);
            }
            return Health.up();
        } catch (IOException e) {
            return Health.down("root is not usable: " + e.getMessage());
        }
    }

    /** {@code <root>/<tenant>/<aa>/<bb>/<contentRef>/<versionId>} */
    Path pathFor(ObjectKey key) {
        return objectRoot(root, key);
    }

    private Path trashPathFor(ObjectKey key) {
        return objectRoot(root.resolve(TRASH_DIR), key);
    }

    private static Path objectRoot(Path base, ObjectKey key) {
        String ref = key.contentRef();
        String first = fanOut(ref, 0);
        String second = fanOut(ref, 2);
        return base.resolve(key.tenant())
                .resolve(first)
                .resolve(second)
                .resolve(ref)
                .resolve(key.versionId());
    }

    private static String fanOut(String ref, int from) {
        if (ref.length() >= from + 2) {
            return ref.substring(from, from + 2);
        }
        return "__";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The write already failed; a leftover temp file is the orphan collector's problem
            // and is never visible as an object.
        }
    }
}
