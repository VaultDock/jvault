package dev.jvault.domain.common;

/**
 * Sensitivity of a piece of ticket content, ordered from least to most sensitive.
 *
 * <p>Ordinal order is meaningful: {@link #atLeast} compares by it, and the egress classifier
 * runs in blocking mode at or above a configured threshold (docs/05-content-placement.md 5.6).
 * Insert new values in the right position rather than appending.
 */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
    SECRET;

    /** True when this classification is as sensitive as {@code threshold}, or more so. */
    public boolean atLeast(Classification threshold) {
        return this.ordinal() >= threshold.ordinal();
    }

    /** The more sensitive of the two. Used when combining policy and request-level intent. */
    public static Classification moreSensitive(Classification a, Classification b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
