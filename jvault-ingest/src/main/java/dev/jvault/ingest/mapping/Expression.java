package dev.jvault.ingest.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A restricted path expression over an event document.
 *
 * <p><strong>Deliberately not a scripting language.</strong> Mappings are configuration authored
 * by administrators and stored in a database; an embedded script engine would turn that
 * configuration into a remote-code-execution surface, which is a poor trade for the convenience
 * of arbitrary transforms (docs/07-kafka.md 7.3).
 *
 * <p>What is supported: dotted paths with array indexing and a wildcard — {@code $.alert.id},
 * {@code $.alert.evidence[0].filename}, {@code $.alert.tags[*]}. Nothing else. An expression is
 * parsed once at configuration time, so a malformed one is rejected on save rather than at three
 * in the morning when the first event arrives.
 */
public final class Expression {

    private final String source;
    private final List<Step> steps;

    private Expression(String source, List<Step> steps) {
        this.source = source;
        this.steps = List.copyOf(steps);
    }

    public static Expression parse(String source) {
        Objects.requireNonNull(source, "source");
        String trimmed = source.trim();
        if (!trimmed.startsWith("$")) {
            throw new MappingConfigurationException(
                    "expression must start with '$': " + source);
        }

        var steps = new ArrayList<Step>();
        String remainder = trimmed.substring(1);

        int at = 0;
        while (at < remainder.length()) {
            char c = remainder.charAt(at);
            if (c == '.') {
                int end = at + 1;
                while (end < remainder.length()
                        && remainder.charAt(end) != '.' && remainder.charAt(end) != '[') {
                    end++;
                }
                String name = remainder.substring(at + 1, end);
                if (name.isEmpty()) {
                    throw new MappingConfigurationException("empty field name in: " + source);
                }
                steps.add(Step.field(name));
                at = end;
            } else if (c == '[') {
                int close = remainder.indexOf(']', at);
                if (close < 0) {
                    throw new MappingConfigurationException("unclosed '[' in: " + source);
                }
                String index = remainder.substring(at + 1, close).trim();
                steps.add("*".equals(index) ? Step.wildcard() : Step.index(parseIndex(index, source)));
                at = close + 1;
            } else {
                throw new MappingConfigurationException(
                        "unexpected character '" + c + "' in: " + source);
            }
        }
        return new Expression(trimmed, steps);
    }

    private static int parseIndex(String index, String source) {
        try {
            int value = Integer.parseInt(index);
            if (value < 0) {
                throw new MappingConfigurationException("negative array index in: " + source);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new MappingConfigurationException("invalid array index '" + index + "' in: " + source);
        }
    }

    /** The single value at this path, or empty when any step is missing. */
    public Optional<JsonNode> evaluate(JsonNode root) {
        List<JsonNode> all = evaluateAll(root);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every value the path selects. More than one only when a wildcard is used. */
    public List<JsonNode> evaluateAll(JsonNode root) {
        List<JsonNode> current = root == null ? List.of() : List.of(root);

        for (Step step : steps) {
            var next = new ArrayList<JsonNode>();
            for (JsonNode node : current) {
                step.apply(node, next);
            }
            if (next.isEmpty()) {
                return List.of();
            }
            current = next;
        }
        return current;
    }

    /** The value as text, with the JSON null node treated as absent rather than as "null". */
    public Optional<String> text(JsonNode root) {
        return evaluate(root)
                .filter(node -> !node.isNull())
                .map(node -> node.isValueNode() ? node.asText() : node.toString());
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    private record Step(Kind kind, String name, int index) {

        private enum Kind {FIELD, INDEX, WILDCARD}

        static Step field(String name) {
            return new Step(Kind.FIELD, name, -1);
        }

        static Step index(int index) {
            return new Step(Kind.INDEX, null, index);
        }

        static Step wildcard() {
            return new Step(Kind.WILDCARD, null, -1);
        }

        void apply(JsonNode node, List<JsonNode> out) {
            switch (kind) {
                case FIELD -> {
                    JsonNode child = node.get(name);
                    if (child != null) {
                        out.add(child);
                    }
                }
                case INDEX -> {
                    if (node.isArray() && index < node.size()) {
                        out.add(node.get(index));
                    }
                }
                case WILDCARD -> {
                    if (node.isArray()) {
                        node.forEach(out::add);
                    }
                }
            }
        }
    }

    /** Human-readable, and safe: names the expression, never the event. */
    public static String describe(String field, String expression) {
        return field + " <- " + expression;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
