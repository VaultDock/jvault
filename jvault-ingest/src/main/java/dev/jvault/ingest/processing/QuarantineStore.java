package dev.jvault.ingest.processing;

/**
 * Holds the original payload of a message that could not be processed.
 *
 * <p>The production implementation writes through the ordinary content path — encrypted, in the
 * configured backend — under an ACL restricted to an incident-responder role, with its own short
 * retention (default 14 days) and an audit event on every read
 * (docs/05-content-placement.md 5.6).
 *
 * <p>This exists so that dead-lettering does not have to choose between losing the evidence and
 * republishing sensitive source data to a topic with different access control. Replay reads from
 * here.
 */
public interface QuarantineStore {

    /**
     * @return an opaque reference recorded in the dead-letter record
     */
    String quarantine(String correlationId, byte[] payload);

    /** Reads a quarantined payload back. Every call is audited. */
    byte[] read(String quarantineRef);
}
