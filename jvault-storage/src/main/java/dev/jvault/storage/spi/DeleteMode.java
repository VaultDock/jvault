package dev.jvault.storage.spi;

/** How thoroughly to remove an object. */
public enum DeleteMode {

    /**
     * Reversible within the retention window. jvault implements this itself wherever the backend
     * does not, so that restore behaves the same on every route.
     */
    SOFT,

    /**
     * Bytes gone. Blocked by legal hold on every path, including administrative purge
     * (docs/08-storage.md 8.6).
     */
    HARD
}
