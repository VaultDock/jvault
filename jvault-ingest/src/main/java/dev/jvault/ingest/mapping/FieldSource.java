package dev.jvault.ingest.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where one Jira field's value comes from.
 *
 * <p>A sealed set rather than an expression language. Each variant is a shape an administrator
 * actually needs — a path, a constant, a concatenation, a lookup table — and adding a fifth is a
 * deliberate act rather than something a clever mapping can invent. That is what keeps mapping
 * configuration from becoming a code-execution surface (docs/07-kafka.md 7.3).
 */
public sealed interface FieldSource {

    /**
     * @return the resolved value, or empty when the source found nothing. Empty is not an error
     *         here; whether a missing value is fatal is the field rule's decision, not the
     *         source's
     */
    Optional<String> resolve(JsonNode event);

    /** A constant. */
    record Literal(String value) implements FieldSource {

        public Literal {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Optional<String> resolve(JsonNode event) {
            return Optional.of(value);
        }
    }

    /** A path into the event. */
    record Path(Expression expression) implements FieldSource {

        public Path {
            Objects.requireNonNull(expression, "expression");
        }

        public static Path of(String expression) {
            return new Path(Expression.parse(expression));
        }

        @Override
        public Optional<String> resolve(JsonNode event) {
            return expression.text(event);
        }
    }

    /**
     * Several sources joined together.
     *
     * <p>A part that resolves to nothing contributes nothing rather than aborting the join, so a
     * summary built from an optional severity still produces a usable title when that severity is
     * missing.
     */
    record Concat(List<FieldSource> parts) implements FieldSource {

        public Concat {
            parts = List.copyOf(parts);
        }

        public static Concat of(FieldSource... parts) {
            return new Concat(List.of(parts));
        }

        @Override
        public Optional<String> resolve(JsonNode event) {
            var joined = new StringBuilder();
            for (FieldSource part : parts) {
                part.resolve(event).ifPresent(joined::append);
            }
            String result = joined.toString();
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        }
    }

    /**
     * A value translated through a table — an event severity into a Jira priority id, say.
     *
     * <p>The table is the whole point: Jira ids are instance-specific, so a mapping that embedded
     * them in a path expression would break on every other deployment.
     */
    record Lookup(Expression from, Map<String, String> table, String fallback)
            implements FieldSource {

        public Lookup {
            Objects.requireNonNull(from, "from");
            table = Map.copyOf(table);
        }

        @Override
        public Optional<String> resolve(JsonNode event) {
            return from.text(event)
                    .map(key -> table.getOrDefault(key, fallback))
                    .or(() -> Optional.ofNullable(fallback))
                    .filter(Objects::nonNull);
        }
    }
}
