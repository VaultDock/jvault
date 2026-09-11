package dev.jvault.content;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PartType;

import java.util.Objects;

/**
 * Everything about a part except its bytes.
 *
 * <p>Separated from the content itself so that storing can take a stream: an attachment may be a
 * gigabyte, and a record holding an {@code InputStream} is a record that cannot be retried.
 *
 * @param displayName the original filename, carried as a {@link SensitiveValue} because it very
 *                    often is one — {@code 2026-Q3-layoffs-final.xlsx} tells you most of what you
 *                    wanted to know without opening it. It never reaches a storage key and never
 *                    reaches Jira; the persistence adapter is responsible for encrypting the
 *                    column it lands in (docs/04-data-model.md 4.2)
 */
public record PartDescriptor(String ticketRef,
                             PartType partType,
                             String fieldKey,
                             SensitiveValue displayName,
                             String mediaType,
                             Classification classification,
                             String keyRing,
                             String jiraSurrogate) {

    public PartDescriptor {
        Objects.requireNonNull(ticketRef, "ticketRef");
        Objects.requireNonNull(partType, "partType");
        Objects.requireNonNull(keyRing, "keyRing");
        mediaType = mediaType == null ? "application/octet-stream" : mediaType;
    }

    /** The same descriptor with the Jira-visible surrogate attached. */
    public PartDescriptor withSurrogate(String surrogate) {
        return new PartDescriptor(ticketRef, partType, fieldKey, displayName, mediaType,
                classification, keyRing, surrogate);
    }

    public static PartDescriptor text(String ticketRef, PartType partType, String fieldKey,
                                      Classification classification, String keyRing) {
        return new PartDescriptor(ticketRef, partType, fieldKey, null, "text/plain",
                classification, keyRing, null);
    }
}
