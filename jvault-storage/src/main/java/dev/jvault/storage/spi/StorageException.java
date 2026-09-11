package dev.jvault.storage.spi;

/** A backend operation failed. Carries identifiers and a code, never content. */
public class StorageException extends RuntimeException {

    private final String code;

    public StorageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public StorageException(String code, String message) {
        this(code, message, null);
    }

    public String code() {
        return code;
    }

    public static StorageException notFound(StoredObjectRef ref) {
        return new StorageException("OBJECT_NOT_FOUND", "no object at " + ref.key());
    }

    public static StorageException alreadyExists(StoredObjectRef ref) {
        return new StorageException("OBJECT_ALREADY_EXISTS", "object already at " + ref.key());
    }
}
