package dev.jvault.ingest.mapping;

import dev.jvault.domain.placement.Placement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How events on one topic become tickets.
 *
 * <p>Configuration, not code: an administrator edits this to onboard a new producer, and nothing
 * about a new topic should require a deployment.
 *
 * @param dedupeKeyPaths paths whose values, joined, identify the business event. This is what
 *                       makes a replay harmless — the unique constraint on the resulting key is
 *                       the only thing standing between a redelivered message and a duplicate
 *                       ticket (docs/07-kafka.md 7.4)
 * @param onExistingKey  whether a second event with the same correlation updates the existing
 *                       ticket, creates another, or is ignored. Incident feeds want UPDATE;
 *                       audit-event feeds usually want CREATE; repeated build failures want
 *                       IGNORE, or Jira fills with noise
 * @param requiredPaths  paths that must be present. Checked before any mapping runs, so a
 *                       malformed event is dead-lettered without a Jira call or a content write
 */
public record EventMapping(String id,
                           String topic,
                           String deploymentId,
                           String projectKey,
                           String issueTypeId,
                           List<Expression> dedupeKeyPaths,
                           List<Expression> requiredPaths,
                           Expression actorPath,
                           ActorMode actorMode,
                           List<FieldRule> fields,
                           ExistingKeyPolicy onExistingKey) {

    public EventMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(issueTypeId, "issueTypeId");
        dedupeKeyPaths = List.copyOf(dedupeKeyPaths);
        requiredPaths = requiredPaths == null ? List.of() : List.copyOf(requiredPaths);
        fields = List.copyOf(fields);
        actorMode = actorMode == null ? ActorMode.RECORD_ONLY : actorMode;
        onExistingKey = onExistingKey == null ? ExistingKeyPolicy.CREATE : onExistingKey;

        if (dedupeKeyPaths.isEmpty()) {
            throw new MappingConfigurationException("mapping '" + id
                    + "' declares no dedupe key; without one a replayed message would create a"
                    + " second ticket");
        }
        if (fields.stream().noneMatch(rule -> "summary".equals(rule.fieldKey()))) {
            throw new MappingConfigurationException("mapping '" + id
                    + "' does not map 'summary'; Jira requires a summary on every issue");
        }
    }

    /** What to do with the originating actor. */
    public enum ActorMode {
        /**
         * Record the actor in jvault and in the {@code jvault.origin} property, but do not put it
         * in Jira-visible text. The default, because an actor's e-mail address is itself often
         * something a project would rather not publish.
         */
        RECORD_ONLY,

        /**
         * Additionally set Jira's Reporter when the actor resolves to a Jira account and the
         * integration identity holds Modify Reporter. Degrades to RECORD_ONLY otherwise, with a
         * metric, so the gap is visible rather than silent.
         */
        SET_REPORTER_IF_PERMITTED
    }

    public enum ExistingKeyPolicy {CREATE, UPDATE, IGNORE}

    /**
     * One Jira field's rule.
     *
     * @param required         a missing value dead-letters the message rather than creating a
     *                         half-populated ticket
     * @param maxLength        Jira's limit for this field; {@code summary} is capped at 255
     * @param requestedPlacement a placement the mapping would like. The policy engine has the
     *                         final word and this can only tighten, never loosen — one source of
     *                         truth for what may appear in Jira
     */
    public record FieldRule(String fieldKey,
                            FieldSource source,
                            boolean required,
                            Integer maxLength,
                            OverflowPolicy onOverflow,
                            Placement requestedPlacement) {

        public FieldRule {
            Objects.requireNonNull(fieldKey, "fieldKey");
            Objects.requireNonNull(source, "source");
            onOverflow = onOverflow == null ? OverflowPolicy.TRUNCATE_ELLIPSIS : onOverflow;
        }

        public static FieldRule of(String fieldKey, FieldSource source) {
            return new FieldRule(fieldKey, source, false, null, null, null);
        }
    }

    public enum OverflowPolicy {
        /** Shorten and mark it, so the reader knows something was cut. */
        TRUNCATE_ELLIPSIS,
        /** Refuse the message. For fields where a truncated value would be misleading. */
        REJECT
    }

    public static Builder builder(String id, String topic, String projectKey, String issueTypeId) {
        return new Builder(id, topic, projectKey, issueTypeId);
    }

    public static final class Builder {
        private final String id;
        private final String topic;
        private final String projectKey;
        private final String issueTypeId;
        private String deploymentId = "default";
        private final List<Expression> dedupeKeyPaths = new ArrayList<>();
        private final List<Expression> requiredPaths = new ArrayList<>();
        private final Map<String, FieldRule> fields = new LinkedHashMap<>();
        private Expression actorPath;
        private ActorMode actorMode = ActorMode.RECORD_ONLY;
        private ExistingKeyPolicy onExistingKey = ExistingKeyPolicy.CREATE;

        private Builder(String id, String topic, String projectKey, String issueTypeId) {
            this.id = id;
            this.topic = topic;
            this.projectKey = projectKey;
            this.issueTypeId = issueTypeId;
        }

        public Builder deployment(String v) { this.deploymentId = v; return this; }
        public Builder dedupeKey(String... paths) {
            for (String path : paths) {
                dedupeKeyPaths.add(Expression.parse(path));
            }
            return this;
        }
        public Builder require(String... paths) {
            for (String path : paths) {
                requiredPaths.add(Expression.parse(path));
            }
            return this;
        }
        public Builder actor(String path, ActorMode mode) {
            this.actorPath = Expression.parse(path);
            this.actorMode = mode;
            return this;
        }
        public Builder field(FieldRule rule) { fields.put(rule.fieldKey(), rule); return this; }
        public Builder field(String key, FieldSource source) {
            return field(FieldRule.of(key, source));
        }
        public Builder onExistingKey(ExistingKeyPolicy v) { this.onExistingKey = v; return this; }

        public EventMapping build() {
            return new EventMapping(id, topic, deploymentId, projectKey, issueTypeId,
                    dedupeKeyPaths, requiredPaths, actorPath, actorMode,
                    List.copyOf(fields.values()), onExistingKey);
        }
    }
}
