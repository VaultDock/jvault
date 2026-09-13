package dev.jvault.jira.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/**
 * Rebuilds an Atlassian Document Format document from only the parts jvault will vouch for.
 *
 * <p>The editor in the browser performs the same filtering, and that is not a reason to skip it
 * here. The browser's version exists so the user sees what will be sent; this one exists because
 * the request body is whatever the caller chose to put in it. A rich-text field is the one place
 * in the create payload where a caller supplies structure rather than a scalar, so it is the one
 * place where "the client already checked" would be a hole rather than a redundancy.
 *
 * <p>Nothing is edited in place. The output is built node by node from a closed allow-list, so an
 * attribute nobody thought about cannot survive by not having been considered.
 *
 * <p>An unknown node is unwrapped rather than dropped: its text is kept and its wrapper discarded.
 * Losing formatting is a nuisance; losing the words someone wrote is a bug.
 */
public final class AdfSanitizer {

    /** Deep enough for any document a person writes, shallow enough not to exhaust the stack. */
    private static final int MAX_DEPTH = 24;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Set<String> BLOCKS = Set.of(
            "paragraph", "heading", "bulletList", "orderedList", "listItem",
            "blockquote", "codeBlock", "rule", "hardBreak");

    private static final Set<String> MARKS = Set.of(
            "strong", "em", "strike", "code", "underline", "link");

    private AdfSanitizer() {
    }

    /** Whether a field's string value is an ADF document rather than plain text. */
    public static boolean isDocument(JsonNode value) {
        return value != null && value.isObject() && "doc".equals(value.path("type").asText());
    }

    /** A document containing only allowed nodes, marks and attributes. */
    public static ObjectNode sanitize(JsonNode document) {
        ObjectNode result = NODES.objectNode();
        result.put("type", "doc");
        result.put("version", 1);
        ArrayNode content = result.putArray("content");

        appendAll(content, document.path("content"), 0);

        // An empty document must still be well formed. Jira rejects a malformed one, and the
        // rejection strands the ticket in the outbox rather than failing in front of the user.
        if (content.isEmpty()) {
            content.add(emptyParagraph());
        }
        return result;
    }

    private static void appendAll(ArrayNode target, JsonNode nodes, int depth) {
        if (!nodes.isArray() || depth > MAX_DEPTH) {
            return;
        }
        for (JsonNode node : nodes) {
            append(target, node, depth);
        }
    }

    private static void append(ArrayNode target, JsonNode node, int depth) {
        String type = node.path("type").asText("");

        if ("text".equals(type)) {
            appendText(target, node);
            return;
        }
        if (!BLOCKS.contains(type)) {
            // Keep the words, drop the wrapper.
            appendAll(target, node.path("content"), depth + 1);
            return;
        }

        switch (type) {
            case "rule", "hardBreak" -> target.add(NODES.objectNode().put("type", type));
            case "codeBlock" -> target.add(codeBlock(node));
            case "heading" -> {
                ObjectNode heading = NODES.objectNode().put("type", "heading");
                heading.putObject("attrs").put("level", headingLevel(node));
                addContent(heading, node, depth);
                target.add(heading);
            }
            case "orderedList" -> {
                ObjectNode list = NODES.objectNode().put("type", "orderedList");
                int order = node.path("attrs").path("order").asInt(1);
                if (order > 1) {
                    list.putObject("attrs").put("order", order);
                }
                target.add(withListItems(list, node, depth));
            }
            case "bulletList" ->
                    target.add(withListItems(NODES.objectNode().put("type", "bulletList"),
                            node, depth));
            // Both must hold blocks. Bare text inside a list item is valid in some ProseMirror
            // schemas and invalid ADF everywhere.
            case "listItem", "blockquote" ->
                    target.add(blockContainer(NODES.objectNode().put("type", type), node, depth));
            default -> {
                ObjectNode block = NODES.objectNode().put("type", type);
                addContent(block, node, depth);
                target.add(block);
            }
        }
    }

    private static void appendText(ArrayNode target, JsonNode node) {
        String value = node.path("text").asText("");
        if (value.isEmpty()) {
            // ADF has no empty text node, and one invalidates the document that contains it.
            return;
        }
        ObjectNode text = NODES.objectNode();
        text.put("type", "text");
        text.put("text", value);

        ArrayNode marks = NODES.arrayNode();
        for (JsonNode mark : node.path("marks")) {
            String type = mark.path("type").asText("");
            if (!MARKS.contains(type)) {
                continue;
            }
            if ("link".equals(type)) {
                String href = mark.path("attrs").path("href").asText("");
                if (isSafeHref(href)) {
                    ObjectNode link = NODES.objectNode().put("type", "link");
                    link.putObject("attrs").put("href", href);
                    marks.add(link);
                }
                continue;
            }
            marks.add(NODES.objectNode().put("type", type));
        }
        if (!marks.isEmpty()) {
            text.set("marks", marks);
        }
        target.add(text);
    }

    /**
     * Only schemes that mean "somewhere else on the web".
     *
     * <p>A {@code javascript:} href in a description is a stored payload aimed at whoever opens
     * the issue. Jira's own renderer filters these, which is exactly the kind of protection that
     * should not be the only one.
     */
    private static boolean isSafeHref(String href) {
        String trimmed = href.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("mailto:")
                || trimmed.startsWith("/");
    }

    private static ObjectNode codeBlock(JsonNode node) {
        ObjectNode block = NODES.objectNode().put("type", "codeBlock");
        String language = node.path("attrs").path("language").asText("");
        if (!language.isBlank()) {
            block.putObject("attrs").put("language", language);
        }
        // Code carries no marks in ADF; bold inside code is not a thing.
        String text = collectText(node);
        if (!text.isEmpty()) {
            block.putArray("content")
                    .add(NODES.objectNode().put("type", "text").put("text", text));
        }
        return block;
    }

    private static String collectText(JsonNode node) {
        if (node.has("text")) {
            return node.path("text").asText("");
        }
        var text = new StringBuilder();
        for (JsonNode child : node.path("content")) {
            text.append(collectText(child));
        }
        return text.toString();
    }

    private static int headingLevel(JsonNode node) {
        int level = node.path("attrs").path("level").asInt(1);
        return level >= 1 && level <= 6 ? level : 1;
    }

    private static void addContent(ObjectNode shell, JsonNode node, int depth) {
        ArrayNode content = NODES.arrayNode();
        appendAll(content, node.path("content"), depth + 1);
        if (!content.isEmpty()) {
            shell.set("content", content);
        }
    }

    private static ObjectNode withListItems(ObjectNode shell, JsonNode node, int depth) {
        ArrayNode converted = NODES.arrayNode();
        appendAll(converted, node.path("content"), depth + 1);

        ArrayNode items = shell.putArray("content");
        for (JsonNode child : converted) {
            if ("listItem".equals(child.path("type").asText())) {
                items.add(child);
            }
        }
        if (items.isEmpty()) {
            items.add(blockContainer(NODES.objectNode().put("type", "listItem"),
                    NODES.objectNode(), depth));
        }
        return shell;
    }

    private static ObjectNode blockContainer(ObjectNode shell, JsonNode node, int depth) {
        ArrayNode converted = NODES.arrayNode();
        appendAll(converted, node.path("content"), depth + 1);

        ArrayNode content = shell.putArray("content");
        for (JsonNode child : converted) {
            String type = child.path("type").asText();
            if (!"text".equals(type) && !"hardBreak".equals(type)) {
                content.add(child);
            }
        }
        if (content.isEmpty()) {
            content.add(emptyParagraph());
        }
        return shell;
    }

    private static ObjectNode emptyParagraph() {
        return NODES.objectNode().put("type", "paragraph");
    }
}
