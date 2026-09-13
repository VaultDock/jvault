package dev.jvault.outbox.support;

import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.jira.egress.JiraWriteRequest;
import dev.jvault.outbox.JiraPayloadAssembler;
import dev.jvault.outbox.OutboxEntry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Assembles a simple request from an entry, with hooks for the two cases that matter: an effect
 * that is no longer applicable, and an assembly that (wrongly) includes external content, so the
 * dispatcher's egress handling can be exercised.
 */
public final class StubPayloadAssembler implements JiraPayloadAssembler {

    private final Map<String, String> summaries = new HashMap<>();
    private final Set<String> notApplicable = new HashSet<>();
    private final Set<String> unexpected = new HashSet<>();
    private final Map<String, SensitiveValue> leaks = new HashMap<>();
    private final Map<String, SensitiveValue> externalParts = new HashMap<>();
    private Classification classification = Classification.INTERNAL;

    public StubPayloadAssembler summary(String effectKey, String summary) {
        summaries.put(effectKey, summary);
        return this;
    }

    public StubPayloadAssembler notApplicable(String effectKey) {
        notApplicable.add(effectKey);
        return this;
    }

    /**
     * Fails in a way nobody declared: assembling reads content, metadata and keys, and any of
     * those can throw something the dispatcher was not written to expect.
     */
    public StubPayloadAssembler throwsUnexpectedly(String effectKey) {
        unexpected.add(effectKey);
        return this;
    }

    /** Simulates the bug the egress guard exists to catch: external content in a Jira payload. */
    public StubPayloadAssembler leaksExternalContent(String effectKey, SensitiveValue value) {
        leaks.put(effectKey, value);
        externalParts.put(effectKey, value);
        return this;
    }

    public StubPayloadAssembler withExternalPart(String effectKey, SensitiveValue value) {
        externalParts.put(effectKey, value);
        return this;
    }

    public StubPayloadAssembler classification(Classification c) {
        this.classification = c;
        return this;
    }

    @Override
    public JiraWriteRequest assemble(OutboxEntry entry) throws EffectNoLongerApplicable {
        if (notApplicable.contains(entry.effectKey())) {
            throw new EffectNoLongerApplicable("PART_DELETED");
        }
        if (unexpected.contains(entry.effectKey())) {
            throw new IllegalStateException("the content key could not be unwrapped");
        }
        var builder = JiraWriteRequest.builder(entry.operation(), entry.ticketRef())
                .issueLane(entry.issueLane())
                .classification(classification)
                .field("summary", summaries.getOrDefault(entry.effectKey(),
                        "Ticket " + entry.ticketRef()));

        SensitiveValue external = externalParts.get(entry.effectKey());
        if (external != null) {
            builder.externalValue(external);
        }
        SensitiveValue leak = leaks.get(entry.effectKey());
        if (leak != null) {
            builder.field("description", leak.reveal());
        }
        return builder.build();
    }
}
