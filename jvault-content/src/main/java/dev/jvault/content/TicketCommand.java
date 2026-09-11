package dev.jvault.content;

import dev.jvault.domain.placement.Placement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A request to create a ticket, from whichever entry point.
 *
 * <p>Identical for the UI, the REST API and the Kafka consumer — that is the whole point. The
 * three adapters differ in how they build this object and in which identity acts; everything
 * after that is one code path, which is what makes "consistent behaviour across all entry points"
 * a property rather than an aspiration (docs/06-rest-api.md 6.3).
 *
 * @param dedupeKey    business idempotency key. The unique constraint on it is the only thing
 *                     standing between a replayed Kafka message and a duplicate ticket
 * @param correlationId flows to the Jira issue property, the audit trail and the status API, so
 *                     one identifier answers "what happened to this event" across all of them
 * @param overrides    per-field placement requests, honoured only where policy allows and only
 *                     toward more protection
 */
public record TicketCommand(String deploymentId,
                            String projectKey,
                            String issueTypeId,
                            Map<String, String> fields,
                            Map<String, Placement> overrides,
                            String dedupeKey,
                            String correlationId,
                            Origin origin) {

    public TicketCommand {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(issueTypeId, "issueTypeId");
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /**
     * Where the ticket came from and who caused it.
     *
     * <p>The actor is recorded <em>separately</em> from the identity that will act in Jira. For
     * Kafka-originated work the Jira creator is the integration identity, which is accurate — the
     * event created the ticket, not a person — while the originating actor is preserved here and
     * in the {@code jvault.origin} issue property (docs/07-kafka.md 7.6).
     */
    public record Origin(Channel channel, String actorId, String actorDisplayName, String identityRef) {

        public enum Channel {UI, API, KAFKA}

        public static Origin kafka(String actorId, String identityRef) {
            return new Origin(Channel.KAFKA, actorId, actorId, identityRef);
        }

        public static Origin ui(String actorId, String displayName) {
            return new Origin(Channel.UI, actorId, displayName, "USER:" + actorId);
        }
    }

    public static Builder builder(String deploymentId, String projectKey, String issueTypeId) {
        return new Builder(deploymentId, projectKey, issueTypeId);
    }

    public static final class Builder {
        private final String deploymentId;
        private final String projectKey;
        private final String issueTypeId;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, Placement> overrides = new LinkedHashMap<>();
        private String dedupeKey;
        private String correlationId;
        private Origin origin;

        private Builder(String deploymentId, String projectKey, String issueTypeId) {
            this.deploymentId = deploymentId;
            this.projectKey = projectKey;
            this.issueTypeId = issueTypeId;
        }

        public Builder field(String key, String value) { fields.put(key, value); return this; }
        public Builder override(String key, Placement p) { overrides.put(key, p); return this; }
        public Builder dedupeKey(String v) { this.dedupeKey = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder origin(Origin v) { this.origin = v; return this; }

        public TicketCommand build() {
            return new TicketCommand(deploymentId, projectKey, issueTypeId, fields, overrides,
                    dedupeKey, correlationId, origin);
        }
    }
}
