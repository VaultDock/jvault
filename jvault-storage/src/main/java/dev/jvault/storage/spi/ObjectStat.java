package dev.jvault.storage.spi;

import java.time.Instant;

/** Cheap existence and size probe, used by the integrity sweeper and the orphan collector. */
public record ObjectStat(long sizeBytes, Instant lastModified, String backendVersionId) {
}
