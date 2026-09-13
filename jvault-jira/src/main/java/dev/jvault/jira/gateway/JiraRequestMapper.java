package dev.jvault.jira.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.deployment.RichTextCodec;
import dev.jvault.jira.egress.JiraFieldEncoding;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.egress.JiraSafePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a checked payload into an actual HTTP request.
 *
 * <p>Takes only a {@link JiraSafePayload}, so there is no path from raw values to a request body
 * that skips the egress guard. That is the whole reason this class sits on the far side of the
 * boundary rather than being folded into the outbox.
 *
 * <p>Field shapes come from the payload's encodings rather than from inspection. Jira's create
 * body is not a flat map of strings — a project is an object keyed by {@code key}, a priority an
 * object keyed by {@code id}, labels an array — and guessing from the value would work right up
 * until the first field whose text happens to look like an id.
 */
public final class JiraRequestMapper {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JiraDeployment deployment;
    private final RichTextCodec richText;

    public JiraRequestMapper(JiraDeployment deployment) {
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.richText = RichTextCodec.forFormat(deployment.richTextFormat());
    }

    /**
     * @param issueIdOrKey the issue this operation targets, or {@code null} for a create
     */
    public JiraHttpClient.JiraHttpRequest toRequest(JiraSafePayload payload, String issueIdOrKey) {
        Objects.requireNonNull(payload, "payload");
        String path = deployment.pathFor(payload.operation(), issueIdOrKey);

        return switch (payload.operation()) {
            case CREATE_ISSUE -> post(path, createIssueBody(payload));
            case UPDATE_FIELDS -> put(path, updateFieldsBody(payload));
            case ADD_COMMENT -> post(path, commentBody(payload));
            case UPSERT_REMOTE_LINK -> post(path, remoteLinkBody(payload));
            case SET_PROPERTY -> put(path, propertyBody(payload));
            default -> throw new UnsupportedOperationException(
                    payload.operation() + " has no request mapping yet");
        };
    }

    /**
     * The create body, including issue properties.
     *
     * <p>Writing {@code jvault.origin} in the same request that creates the issue is verified to
     * work (docs/00-verified-capabilities.md 0.9) and it matters: it closes the window in which a
     * create succeeds and a follow-up property write does not, which is exactly the gap that
     * pushes the ambiguity protocol onto its weaker summary-matching path.
     */
    private String createIssueBody(JiraSafePayload payload) {
        ObjectNode body = NODES.objectNode();
        body.set("fields", fieldsNode(payload));

        if (!payload.properties().isEmpty()) {
            ArrayNode properties = body.putArray("properties");
            payload.properties().forEach((key, json) -> {
                ObjectNode property = NODES.objectNode();
                property.put("key", key);
                property.set("value", parse(json));
                properties.add(property);
            });
        }
        return body.toString();
    }

    private String updateFieldsBody(JiraSafePayload payload) {
        ObjectNode body = NODES.objectNode();
        body.set("fields", fieldsNode(payload));
        return body.toString();
    }

    private String commentBody(JiraSafePayload payload) {
        ObjectNode body = NODES.objectNode();
        body.set("body", richText.encode(payload.textFields().getOrDefault("body", "")));
        return body.toString();
    }

    /**
     * A remote link, identified by {@code globalId}.
     *
     * <p>Jira upserts on that id — verified empirically — so this request is safe for the outbox
     * to replay: a second attempt updates the existing link instead of adding a duplicate
     * (docs/00-verified-capabilities.md 0.6).
     */
    private String remoteLinkBody(JiraSafePayload payload) {
        Map<String, String> fields = payload.textFields();
        ObjectNode body = NODES.objectNode();
        body.put("globalId", fields.getOrDefault("globalId", ""));

        ObjectNode object = body.putObject("object");
        object.put("url", fields.getOrDefault("url", ""));
        object.put("title", fields.getOrDefault("title", "jvault content"));
        if (fields.containsKey("summary")) {
            object.put("summary", fields.get("summary"));
        }
        return body.toString();
    }

    private String propertyBody(JiraSafePayload payload) {
        // The property endpoint takes the value as the whole body, not wrapped.
        String single = payload.properties().values().stream().findFirst().orElse("{}");
        return single;
    }

    private ObjectNode fieldsNode(JiraSafePayload payload) {
        ObjectNode fields = NODES.objectNode();
        payload.textFields().forEach((key, value) -> {
            if (isStructuralOnly(key)) {
                return;
            }
            fields.set(key, encode(key, value, payload.encodingOf(key)));
        });
        return fields;
    }

    private JsonNode encode(String fieldKey, String value, JiraFieldEncoding encoding) {
        return switch (encoding) {
            case STRING -> NODES.textNode(value);
            case RICH_TEXT -> richText.encode(value);
            case ID_OBJECT -> NODES.objectNode().put("id", value);
            case KEY_OBJECT -> NODES.objectNode().put("key", value);
            case ACCOUNT_OBJECT -> NODES.objectNode().put("accountId", value);
            case NUMBER -> numberOrText(value);
            case STRING_ARRAY -> {
                ArrayNode array = NODES.arrayNode();
                for (String item : items(value)) {
                    array.add(item);
                }
                yield array;
            }
            case ID_OBJECT_ARRAY -> {
                ArrayNode array = NODES.arrayNode();
                for (String item : items(value)) {
                    array.add(NODES.objectNode().put("id", item));
                }
                yield array;
            }
            case ACCOUNT_OBJECT_ARRAY -> {
                ArrayNode array = NODES.arrayNode();
                for (String item : items(value)) {
                    array.add(NODES.objectNode().put("accountId", item));
                }
                yield array;
            }
        };
    }

    /**
     * The elements of a multi-valued field.
     *
     * <p>Comma-separated, which is what the form sends and what every array encoding here
     * splits on. Empty elements are dropped rather than sent: a trailing comma is a typing
     * artefact, and an empty option id is a field error waiting to happen.
     */
    private static List<String> items(String value) {
        var found = new ArrayList<String>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                found.add(trimmed);
            }
        }
        return found;
    }

    private static JsonNode numberOrText(String value) {
        try {
            return NODES.numberNode(new java.math.BigDecimal(value));
        } catch (NumberFormatException e) {
            // Sending it as text lets Jira produce a field-level error naming the field, which is
            // more useful to the operator than a mapping exception naming nothing.
            return NODES.textNode(value);
        }
    }

    /** Keys that carry routing information rather than a Jira field value. */
    private static boolean isStructuralOnly(String key) {
        return "globalId".equals(key) || "url".equals(key) || "title".equals(key)
                || "body".equals(key);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            // A property value that will not parse is a programming error, not a user one, and
            // sending malformed JSON would produce a baffling 400 from Jira instead.
            throw new IllegalArgumentException("property value is not valid JSON", e);
        }
    }

    private static JiraHttpClient.JiraHttpRequest post(String path, String body) {
        return new JiraHttpClient.JiraHttpRequest("POST", path, body, Map.of());
    }

    private static JiraHttpClient.JiraHttpRequest put(String path, String body) {
        return new JiraHttpClient.JiraHttpRequest("PUT", path, body, Map.of());
    }
}
