package dev.jvault.ingest.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.jvault.domain.placement.Placement;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads mapping configuration from YAML.
 *
 * <p>The format is the one in docs/examples/kafka-mappings.yaml, and this implements the part of
 * it the pipeline actually supports.
 *
 * <p><strong>Anything it does not implement is an error, not something to skip.</strong> A file
 * that says {@code placement: EXTERNAL} under a key this loader silently ignored would produce a
 * mapping that writes to Jira what the author believed was going to the vault. There is no safe
 * way to partially understand a configuration whose whole purpose is deciding where content
 * goes, so an unknown key stops the deployment rather than the data.
 */
public final class EventMappingLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Every key this loader knows what to do with. Anything else is refused by name. */
    private static final Set<String> MAPPING_KEYS = Set.of(
            "id", "topic", "consumerGroup", "concurrency", "deployment", "target",
            "idempotency", "actor", "fields", "guards");

    private static final Set<String> FIELD_KEYS = Set.of(
            "expr", "literal", "lookup", "maxLength", "onOverflow", "placement", "required");

    private static final Pattern CONCAT = Pattern.compile("^concat\\((.*)\\)$", Pattern.DOTALL);

    private EventMappingLoader() {
    }

    public static List<EventMapping> load(InputStream yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            // The parser's message quotes the offending line, which is configuration rather
            // than content, and is exactly what an administrator needs to fix it.
            throw new MappingConfigurationException(
                    "the mapping file could not be read as YAML: " + e.getMessage());
        }

        JsonNode mappings = root.path("mappings");
        if (!mappings.isArray() || mappings.isEmpty()) {
            throw new MappingConfigurationException(
                    "the mapping file declares no mappings; a consumer with no mapping would "
                            + "read a topic and do nothing with it");
        }

        var loaded = new ArrayList<EventMapping>();
        for (JsonNode mapping : mappings) {
            loaded.add(one(mapping));
        }
        return List.copyOf(loaded);
    }

    private static EventMapping one(JsonNode node) {
        String id = required(node, "id");
        refuseUnknown(id, node, MAPPING_KEYS, "mapping");

        JsonNode target = node.path("target");
        var builder = EventMapping.builder(id, required(node, "topic"),
                text(target, "project", id + ".target.project"),
                text(target, "issueType", id + ".target.issueType"));

        if (node.hasNonNull("deployment")) {
            builder.deployment(node.path("deployment").asText());
        }

        JsonNode dedupe = node.path("idempotency").path("dedupeKey");
        if (dedupe.isArray() && !dedupe.isEmpty()) {
            var paths = new ArrayList<String>();
            dedupe.forEach(entry -> paths.add(entry.asText()));
            builder.dedupeKey(paths.toArray(String[]::new));
        }

        JsonNode reject = node.path("guards").path("rejectIfMissing");
        if (reject.isArray() && !reject.isEmpty()) {
            var paths = new ArrayList<String>();
            reject.forEach(entry -> paths.add(entry.asText()));
            builder.require(paths.toArray(String[]::new));
        }

        JsonNode actor = node.path("actor");
        if (actor.hasNonNull("expr")) {
            builder.actor(actor.path("expr").asText(),
                    EventMapping.ActorMode.valueOf(actor.path("mode").asText("RECORD_ONLY")));
        }

        JsonNode fields = node.path("fields");
        if (!fields.isObject() || fields.isEmpty()) {
            throw new MappingConfigurationException(
                    "mapping '" + id + "' declares no fields, so it could not populate a ticket");
        }
        fields.properties().forEach(entry ->
                builder.field(fieldRule(id, entry.getKey(), entry.getValue())));

        return builder.build();
    }

    private static EventMapping.FieldRule fieldRule(String mappingId, String key, JsonNode node) {
        refuseUnknown(mappingId, node, FIELD_KEYS, "field '" + key + "'");

        FieldSource source = sourceOf(mappingId, key, node);
        Integer maxLength = node.hasNonNull("maxLength") ? node.path("maxLength").asInt() : null;

        EventMapping.OverflowPolicy overflow = node.hasNonNull("onOverflow")
                ? EventMapping.OverflowPolicy.valueOf(node.path("onOverflow").asText())
                : null;

        // A declared placement may tighten what policy decided and never loosen it; the resolver
        // enforces that, and declaring it here is how an author says what they intend.
        Placement placement = node.hasNonNull("placement")
                ? Placement.valueOf(node.path("placement").asText())
                : null;

        return new EventMapping.FieldRule(key, source, node.path("required").asBoolean(false),
                maxLength, overflow, placement);
    }

    private static FieldSource sourceOf(String mappingId, String key, JsonNode node) {
        if (node.hasNonNull("literal")) {
            JsonNode literal = node.path("literal");
            if (literal.isArray()) {
                var values = new ArrayList<String>();
                literal.forEach(entry -> values.add(entry.asText()));
                // Jira takes a comma-separated list for the multi-valued fields this appears on.
                return new FieldSource.Literal(String.join(",", values));
            }
            return new FieldSource.Literal(literal.asText());
        }

        if (node.hasNonNull("lookup")) {
            JsonNode lookup = node.path("lookup");
            var table = new LinkedHashMap<String, String>();
            lookup.path("table").properties()
                    .forEach(entry -> table.put(entry.getKey(), entry.getValue().asText()));
            if (table.isEmpty()) {
                throw new MappingConfigurationException("mapping '" + mappingId + "' field '"
                        + key + "' declares a lookup with no table");
            }
            return new FieldSource.Lookup(
                    Expression.parse(text(lookup, "from", mappingId + "." + key + ".lookup.from")),
                    table,
                    lookup.hasNonNull("default") ? lookup.path("default").asText() : null);
        }

        String expression = text(node, "expr", mappingId + "." + key + ".expr");
        Matcher concat = CONCAT.matcher(expression.trim());
        return concat.matches()
                ? concatOf(concat.group(1))
                : FieldSource.Path.of(expression);
    }

    /**
     * {@code concat('[', $.a.b, '] ', $.c)} — quoted literals and paths, comma separated.
     *
     * <p>Split by hand rather than with a general expression parser, because the grammar this
     * accepts is the whole grammar: there is no scripting engine here and there is not going to
     * be one (docs/07-kafka.md).
     */
    private static FieldSource concatOf(String arguments) {
        var parts = new ArrayList<FieldSource>();
        var current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(argument(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(argument(current.toString()));
        }
        if (inQuotes) {
            throw new MappingConfigurationException(
                    "unterminated quote in concat(" + arguments + ")");
        }
        return FieldSource.Concat.of(parts.toArray(FieldSource[]::new));
    }

    private static FieldSource argument(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return new FieldSource.Literal(trimmed.substring(1, trimmed.length() - 1));
        }
        return FieldSource.Path.of(trimmed);
    }

    private static void refuseUnknown(String mappingId, JsonNode node, Set<String> known,
                                      String what) {
        var unknown = new ArrayList<String>();
        node.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new MappingConfigurationException("mapping '" + mappingId + "' uses "
                    + unknown + " in " + what + ", which this version does not implement. "
                    + "Ignoring configuration that decides where content goes is how content "
                    + "ends up somewhere nobody chose; remove it or use a build that supports it.");
        }
    }

    private static String required(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) {
            throw new MappingConfigurationException("a mapping is missing '" + field + "'");
        }
        return node.path(field).asText();
    }

    private static String text(JsonNode node, String field, String where) {
        if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) {
            throw new MappingConfigurationException("missing '" + where + "'");
        }
        return node.path(field).asText();
    }

    /** Consumer settings that belong to the transport rather than to the mapping itself. */
    public static Map<String, Consumer> consumersIn(InputStream yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new MappingConfigurationException("the mapping file could not be read as YAML");
        }
        var consumers = new LinkedHashMap<String, Consumer>();
        for (JsonNode mapping : root.path("mappings")) {
            consumers.put(mapping.path("id").asText(), new Consumer(
                    mapping.path("topic").asText(),
                    mapping.path("consumerGroup").asText("jvault"),
                    Math.max(1, mapping.path("concurrency").asInt(1))));
        }
        return Map.copyOf(consumers);
    }

    public record Consumer(String topic, String groupId, int concurrency) {
    }
}
