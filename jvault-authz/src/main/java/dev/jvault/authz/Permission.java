package dev.jvault.authz;

import java.util.Set;

/**
 * What a principal may do with externally stored content (docs/11-authorization.md 11.1).
 *
 * <p>Note what is <strong>not</strong> here: an implication hierarchy. {@link #DOWNLOAD} is not
 * granted by {@link #VIEW}, and {@link #EDIT} does not grant {@link #DELETE}. Each is asked for
 * explicitly.
 */
public enum Permission {

    /** See that a part exists, its metadata, and its rendered preview. */
    VIEW,

    /** Add new parts to a ticket in this scope. */
    CREATE,

    /** Create a new version of a part. Does not imply {@link #DELETE}. */
    EDIT,

    /** Soft-delete a part, and restore it within retention. Hard purge is admin-only. */
    DELETE,

    /**
     * Retrieve the bytes.
     *
     * <p><strong>Deliberately separate from {@link #VIEW}.</strong> The common real requirement is
     * "this group may read the incident narrative in the browser but must not take a copy of the
     * evidence file onto a laptop". Collapsing the two makes that impossible to express, and it is
     * one of the more frequently requested distinctions in systems holding regulated content.
     */
    DOWNLOAD,

    /** Grant and revoke permissions on this scope. Required in order to delegate. */
    MANAGE_ACCESS;

    /** The set a space administrator holds. */
    public static Set<Permission> all() {
        return Set.of(values());
    }

    /** A sensible read-only set: see it, but do not take a copy. */
    public static Set<Permission> readOnly() {
        return Set.of(VIEW);
    }
}
