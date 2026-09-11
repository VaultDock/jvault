package dev.jvault.ingest.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvault.content.TicketCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns one event into a {@link TicketCommand}.
 *
 * <p>The output is the same command the UI and the REST API build, so an event and a form
 * submission travel identical code from here on — placement resolution, encryption, surrogates,
 * outbox. That is what makes the three-way conformance promise testable rather than aspirational
 * (docs/06-rest-api.md 6.3).
 *
 * <p><strong>Validation failures name the field and the violation, never the value.</strong> The
 * offending value is source data and may be exactly what must not be written down: an event whose
 * severity is malformed might well carry a credential two fields away, and an error message that
 * quoted it would leak straight into a log, a dead-letter record, or a support ticket
 * (docs/01-requirements.md FR-KAF-6).
 */
public final class EventMapper {

    private final EventMapping mapping;

    public EventMapper(EventMapping mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /**
     * @throws EventValidationException listing every problem, so a producer fixing their schema
     *                                  sees all of them at once
     */
    public Mapped map(JsonNode event, String correlationId) {
        Objects.requireNonNull(event, "event");
        var problems = new ArrayList<FieldProblem>();

        // Required paths first: a malformed event must not reach a content write or a Jira call.
        for (Expression required : mapping.requiredPaths()) {
            if (required.evaluate(event).filter(node -> !node.isNull()).isEmpty()) {
                problems.add(new FieldProblem(required.source(), "REQUIRED_PATH_MISSING"));
            }
        }

        String dedupeKey = dedupeKey(event, problems);

        var command = TicketCommand.builder(mapping.deploymentId(), mapping.projectKey(),
                        mapping.issueTypeId())
                .dedupeKey(dedupeKey)
                .correlationId(correlationId);

        for (EventMapping.FieldRule rule : mapping.fields()) {
            Optional<String> resolved = rule.source().resolve(event);

            if (resolved.isEmpty()) {
                if (rule.required()) {
                    problems.add(new FieldProblem(rule.fieldKey(), "REQUIRED_FIELD_MISSING"));
                }
                continue;
            }

            String value = resolved.get();
            if (rule.maxLength() != null && value.length() > rule.maxLength()) {
                if (rule.onOverflow() == EventMapping.OverflowPolicy.REJECT) {
                    problems.add(new FieldProblem(rule.fieldKey(), "VALUE_TOO_LONG"));
                    continue;
                }
                value = truncate(value, rule.maxLength());
            }

            command.field(rule.fieldKey(), value);
            if (rule.requestedPlacement() != null) {
                command.override(rule.fieldKey(), rule.requestedPlacement());
            }
        }

        if (!problems.isEmpty()) {
            throw new EventValidationException(mapping.id(), problems);
        }

        String actor = mapping.actorPath() == null
                ? null
                : mapping.actorPath().text(event).orElse(null);
        command.origin(new TicketCommand.Origin(TicketCommand.Origin.Channel.KAFKA,
                actor, actor, integrationIdentity()));

        return new Mapped(command.build(), dedupeKey, actor);
    }

    private String dedupeKey(JsonNode event, List<FieldProblem> problems) {
        var parts = new ArrayList<String>();
        for (Expression path : mapping.dedupeKeyPaths()) {
            Optional<String> value = path.text(event);
            if (value.isEmpty()) {
                // Without a complete key a replay could not be recognised, so an event missing
                // one is refused rather than processed with a partial key.
                problems.add(new FieldProblem(path.source(), "DEDUPE_KEY_PATH_MISSING"));
            } else {
                parts.add(value.get());
            }
        }
        return String.join("|", parts);
    }

    private String integrationIdentity() {
        // Kafka-originated work always runs as the integration identity; no interactive session
        // is involved and none is required (docs/07-kafka.md 7.6).
        return "INTEGRATION:" + mapping.deploymentId();
    }

    private static String truncate(String value, int max) {
        return max <= 1 ? value.substring(0, max) : value.substring(0, max - 1) + "…";
    }

    /**
     * @param actor the originating actor, recorded separately from the Jira identity that will
     *              create the issue
     */
    public record Mapped(TicketCommand command, String dedupeKey, String actor) {
    }

    /** A field and what was wrong with it. Never the value. */
    public record FieldProblem(String field, String code) {

        @Override
        public String toString() {
            return field + ":" + code;
        }
    }

    /** Carries field-level problems for the dead-letter record, with no payload. */
    public static class EventValidationException extends RuntimeException {

        private final String mappingId;
        private final List<FieldProblem> problems;

        public EventValidationException(String mappingId, List<FieldProblem> problems) {
            super("event does not satisfy mapping '" + mappingId + "': " + problems);
            this.mappingId = mappingId;
            this.problems = List.copyOf(problems);
        }

        public String mappingId() {
            return mappingId;
        }

        public List<FieldProblem> problems() {
            return problems;
        }
    }
}
