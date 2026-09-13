package dev.jvault.jira.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.jvault.jira.egress.AdfSanitizer;

import java.util.Objects;

/**
 * Turns plain text into whatever rich-text shape a deployment expects.
 *
 * <p>The two are not merely different syntaxes for the same thing. ADF is a JSON tree with
 * addressable nodes, which is what makes section-level placement possible on Cloud; wiki markup
 * is an unstructured string, which is why the same feature is refused on Data Center
 * (docs/05-content-placement.md 5.4). Keeping both behind one interface stops that difference
 * leaking into every call site while still letting the capability flags tell the truth about it.
 *
 * <p>ADF is jvault's canonical internal form and wiki markup the lossy projection, because
 * converting a tree to a string loses nothing that a string could have carried, and the reverse
 * is a parsing problem nobody wants.
 */
public interface RichTextCodec {

    /** Encodes plain text for this deployment's API version. */
    JsonNode encode(String plainText);

    static RichTextCodec forFormat(JiraDeployment.RichTextFormat format) {
        return switch (format) {
            case ADF -> new AdfCodec();
            case WIKI_MARKUP -> new WikiMarkupCodec();
        };
    }

    /**
     * Atlassian Document Format, used by REST v3 on Jira Cloud.
     *
     * <p>Produces the minimal valid document: blank-line-separated blocks become paragraphs, and
     * a line break within a block becomes a {@code hardBreak} rather than a new paragraph — which
     * is what a user who pressed Shift+Enter meant.
     *
     * <p>This is deliberately a <em>writer</em> for plain text, not a Markdown converter. A
     * half-hearted Markdown parser here would silently mangle text containing asterisks or
     * underscores (docs/02-jira-parity-scope.md 2.4.4).
     *
     * <p>A value that is already an ADF document — which is what the editor produces — is kept as
     * structure rather than being wrapped in a paragraph as literal JSON. It is rebuilt through
     * {@link AdfSanitizer} first: the browser filters the same document before sending it, but a
     * request body is whatever the caller chose to put in it, and rich text is the one field in
     * the create payload where a caller supplies structure rather than a scalar.
     */
    final class AdfCodec implements RichTextCodec {

        private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public JsonNode encode(String plainText) {
            Objects.requireNonNull(plainText, "plainText");

            JsonNode structured = asDocument(plainText);
            if (structured != null) {
                return AdfSanitizer.sanitize(structured);
            }

            ObjectNode document = NODES.objectNode();
            document.put("version", 1);
            document.put("type", "doc");
            var content = document.putArray("content");

            // An empty description must still be a valid document, not null: Jira rejects a
            // malformed one, and a rejected create would strand the ticket in the outbox.
            if (plainText.isBlank()) {
                content.add(paragraph(""));
                return document;
            }

            for (String block : plainText.split("\\R\\s*\\R")) {
                content.add(paragraph(block));
            }
            return document;
        }

        /**
         * The value as an ADF document, or {@code null} if it is ordinary text.
         *
         * <p>The cheap prefix check comes first so that a description which merely opens with a
         * brace is not run through a JSON parser on every dispatch.
         */
        private static JsonNode asDocument(String value) {
            if (!value.stripLeading().startsWith("{")) {
                return null;
            }
            try {
                JsonNode parsed = JSON.readTree(value);
                return AdfSanitizer.isDocument(parsed) ? parsed : null;
            } catch (Exception e) {
                // Text that looks like JSON and is not. It is still someone's description.
                return null;
            }
        }

        private static ObjectNode paragraph(String block) {
            ObjectNode paragraph = NODES.objectNode();
            paragraph.put("type", "paragraph");
            var inline = paragraph.putArray("content");

            String[] lines = block.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    inline.add(NODES.objectNode().put("type", "hardBreak"));
                }
                if (!lines[i].isEmpty()) {
                    ObjectNode text = NODES.objectNode();
                    text.put("type", "text");
                    text.put("text", lines[i]);
                    inline.add(text);
                }
            }
            return paragraph;
        }
    }

    /**
     * Wiki markup, used by REST v2 on Jira Data Center.
     *
     * <p>The text is sent as-is. No escaping is attempted, and that is a considered choice rather
     * than an omission: wiki markup has no escape mechanism that round-trips cleanly, so anything
     * clever here would corrupt legitimate text more often than it would prevent surprising
     * formatting. Content whose formatting genuinely matters belongs in the vault, where it is
     * stored verbatim.
     */
    final class WikiMarkupCodec implements RichTextCodec {

        @Override
        public JsonNode encode(String plainText) {
            return TextNode.valueOf(Objects.requireNonNull(plainText, "plainText"));
        }
    }
}
