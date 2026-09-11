package dev.jvault.storage.spi;

/**
 * What a backend can do natively.
 *
 * <p>jvault provides versioning, integrity and retention itself, uniformly, so that those
 * features behave identically wherever a policy points (docs/08-storage.md 8.2). Capabilities are
 * consulted only where the difference changes behaviour that must not be faked:
 *
 * <ul>
 *   <li>{@code maxObjectBytes} — an upload too large for a route is rejected up front, not
 *       discovered halfway through a write.</li>
 *   <li>{@code immutabilityLock} — a route configured for records-managed retention requires it.
 *       We will not claim WORM on a filesystem.</li>
 *   <li>{@code rangeReads} — absent, ranged reads still work, by reading from the start and
 *       discarding. Correctness is preserved; throughput is not, and that is worth a warning.</li>
 *   <li>{@code serverSideEncryption} — a second layer under our own. Its absence is not fatal but
 *       it is worth knowing, because on a plain filesystem application-level encryption is the
 *       only confidentiality layer there is.</li>
 * </ul>
 */
public record Capabilities(boolean nativeVersioning,
                           boolean serverSideEncryption,
                           boolean immutabilityLock,
                           boolean rangeReads,
                           boolean conditionalWrites,
                           boolean checksumOnWrite,
                           boolean softDelete,
                           long maxObjectBytes) {
}
