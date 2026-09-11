package dev.jvault.crypto.envelope;

import java.util.Objects;

/**
 * Which object a ciphertext belongs to, bound cryptographically into every segment.
 *
 * <p>Both components are opaque identifiers, never content or filenames — an object key must not
 * reveal anything about what it holds (docs/08-storage.md 8.3).
 */
public record ObjectIdentity(String contentRef, String versionId) {

    public ObjectIdentity {
        Objects.requireNonNull(contentRef, "contentRef");
        Objects.requireNonNull(versionId, "versionId");
        if (contentRef.isBlank() || versionId.isBlank()) {
            throw new IllegalArgumentException("contentRef and versionId must not be blank");
        }
    }
}
