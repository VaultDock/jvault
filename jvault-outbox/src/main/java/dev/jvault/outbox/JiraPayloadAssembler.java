package dev.jvault.outbox;

import dev.jvault.jira.egress.JiraWriteRequest;

/**
 * Turns an outbox entry's identifiers into an actual Jira write request, by re-reading the
 * current values from storage.
 *
 * <p>This indirection is the reason the outbox table never holds content. It also means the
 * egress guard always inspects the bytes that are genuinely about to be sent, rather than a
 * snapshot taken when the effect was enqueued — which matters, because between enqueue and
 * dispatch a placement policy may have changed, content may have been re-classified, or a part
 * may have been deleted.
 */
public interface JiraPayloadAssembler {

    /**
     * @throws EffectNoLongerApplicable when the effect has been overtaken by events — the part
     *                                  was deleted, the ticket was purged. The dispatcher treats
     *                                  this as a successful no-op rather than a failure: the
     *                                  desired end state has been reached by another route.
     */
    JiraWriteRequest assemble(OutboxEntry entry) throws EffectNoLongerApplicable;

    /** Not an error. The effect is moot, and forcing it through would undo a legitimate change. */
    class EffectNoLongerApplicable extends Exception {
        private final String reasonCode;

        public EffectNoLongerApplicable(String reasonCode) {
            super("effect no longer applicable: " + reasonCode);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
