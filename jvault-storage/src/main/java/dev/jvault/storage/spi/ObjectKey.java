package dev.jvault.storage.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where an object lives in a backend: {@code {tenant}/{contentRef}/{versionId}}.
 *
 * <p><strong>No filename, no content-derived component, ever.</strong> An attachment called
 * {@code 2026-Q3-layoffs-final.xlsx} is frequently the most sensitive thing about the file, and a
 * storage key is visible to anyone with bucket listing rights, appears in backup manifests, and
 * is logged by half the tooling that touches object storage. The original filename is encrypted
 * metadata in the database instead (docs/08-storage.md 8.3, docs/04-data-model.md 4.2).
 *
 * <p>The constructor enforces that: components must be opaque identifiers, so the rule cannot be
 * broken by a caller who means well.
 */
public record ObjectKey(String tenant, String contentRef, String versionId) {

    /** Opaque identifiers only: UUIDs, ULIDs. Anything with a dot or a space is rejected. */
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ObjectKey {
        requireOpaque(tenant, "tenant");
        requireOpaque(contentRef, "contentRef");
        requireOpaque(versionId, "versionId");
    }

    private static void requireOpaque(String value, String what) {
        Objects.requireNonNull(value, what);
        if (!OPAQUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    what + " must be an opaque identifier matching " + OPAQUE.pattern()
                            + " — storage keys must never carry filenames or content");
        }
    }

    /** The canonical string form, used by every backend. */
    public String asPath() {
        return tenant + "/" + contentRef + "/" + versionId;
    }

    @Override
    public String toString() {
        return asPath();
    }
}
