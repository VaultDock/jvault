package dev.jvault.storage.spi;

import java.util.Objects;

/**
 * A handle to stored bytes: which backend, and where in it.
 *
 * <p>Separate from the content version it belongs to, so that migrating an object between
 * backends does not disturb version identity or the user-visible link
 * (docs/08-storage.md 8.8).
 */
public record StoredObjectRef(String backendName, ObjectKey key) {

    public StoredObjectRef {
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(key, "key");
    }
}
