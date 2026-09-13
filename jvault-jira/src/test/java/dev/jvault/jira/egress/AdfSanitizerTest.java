package dev.jvault.jira.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rich-text field is the one place in a create payload where the caller supplies structure
 * rather than a scalar, so it is the one place worth attacking. The browser filters the same
 * document before sending it; these tests are about what happens when it did not.
 */
class AdfSanitizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("an unknown node is unwrapped, keeping the words and dropping the wrapper")
    void unknownNodesAreUnwrapped() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[
                  {"type":"mediaSingle","attrs":{"layout":"wide"},"content":[
                    {"type":"paragraph","content":[{"type":"text","text":"kept"}]}]}]}""");

        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("paragraph");
        assertThat(text(result)).isEqualTo("kept");
    }

    @Test
    @DisplayName("attributes nobody allowed do not survive")
    void unknownAttributesAreDropped() throws Exception {
        // The document is rebuilt rather than edited, so an attribute is absent unless something
        // deliberately copied it across. That is the property under test.
        JsonNode result = sanitize("""
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"localId":"x","onclick":"steal()"},
                   "content":[{"type":"text","text":"hi","attrs":{"style":"display:none"}}]}]}""");

        assertThat(result.path("content").get(0).has("attrs")).isFalse();
        assertThat(result.path("content").get(0).path("content").get(0).has("attrs")).isFalse();
    }

    @Test
    @DisplayName("a javascript: link loses its mark and keeps its text")
    void hostileLinksAreRefused() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"click me","marks":[
                    {"type":"link","attrs":{"href":"  JavaScript:alert(1)"}}]}]}]}""");

        JsonNode textNode = result.path("content").get(0).path("content").get(0);
        assertThat(textNode.path("text").asText()).isEqualTo("click me");
        assertThat(textNode.has("marks")).isFalse();
    }

    @Test
    @DisplayName("an ordinary link keeps only its href")
    void ordinaryLinksSurviveWithoutExtras() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"runbook","marks":[
                    {"type":"link","attrs":{"href":"https://example.com/x","target":"_blank",
                     "__proto__":"nope"}}]}]}]}""");

        JsonNode mark = result.path("content").get(0).path("content").get(0)
                .path("marks").get(0);
        assertThat(mark.path("type").asText()).isEqualTo("link");
        assertThat(mark.path("attrs").properties()).hasSize(1);
        assertThat(mark.path("attrs").path("href").asText()).isEqualTo("https://example.com/x");
    }

    @Test
    @DisplayName("an unknown mark is dropped without touching the text")
    void unknownMarksAreDropped() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"plain","marks":[{"type":"textColor",
                   "attrs":{"color":"#ff0000"}}]}]}]}""");

        assertThat(result.path("content").get(0).path("content").get(0).has("marks")).isFalse();
        assertThat(text(result)).isEqualTo("plain");
    }

    @Test
    @DisplayName("a document nested past any reasonable depth does not exhaust the stack")
    void deepNestingIsBounded() throws Exception {
        // Jackson refuses to parse past 1000 levels, so this bound is the second line of defence
        // rather than the first. It is worth having anyway: the first one is a library default
        // that a future configuration change could raise without anyone thinking about this file.
        var deep = new StringBuilder("{\"type\":\"doc\",\"content\":[");
        int depth = 400;
        for (int i = 0; i < depth; i++) {
            deep.append("{\"type\":\"blockquote\",\"content\":[");
        }
        deep.append("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"deep\"}]}");
        deep.append("]}".repeat(depth)).append("]}");

        // The point is that this returns rather than overflowing. The content below the limit is
        // lost, which is the right trade against a request that can take the process down.
        JsonNode result = sanitize(deep.toString());

        assertThat(result.path("type").asText()).isEqualTo("doc");
        assertThat(result.path("content")).isNotEmpty();
        assertThat(text(result)).doesNotContain("deep");
    }

    @Test
    @DisplayName("an empty document is still a valid one")
    void emptyDocumentsStayValid() throws Exception {
        // Jira rejects a malformed document, and the rejection strands the ticket in the outbox
        // rather than failing in front of the user.
        JsonNode result = sanitize("{\"type\":\"doc\",\"content\":[]}");

        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("paragraph");
    }

    @Test
    @DisplayName("empty text nodes, which invalidate a document, are removed")
    void emptyTextNodesAreRemoved() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":""},{"type":"text","text":"kept"}]}]}""");

        assertThat(result.path("content").get(0).path("content")).hasSize(1);
    }

    @Test
    @DisplayName("list items and quotes are given the block content ADF requires")
    void containersHoldBlocks() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[{"type":"bulletList","content":[
                  {"type":"listItem","content":[{"type":"text","text":"loose"}]},
                  {"type":"listItem"}]}]}""");

        JsonNode items = result.path("content").get(0).path("content");
        assertThat(items).hasSize(2);
        for (JsonNode item : items) {
            assertThat(item.path("content").get(0).path("type").asText()).isEqualTo("paragraph");
        }
    }

    @Test
    @DisplayName("code block text carries no marks")
    void codeCarriesNoMarks() throws Exception {
        JsonNode result = sanitize("""
                {"type":"doc","content":[{"type":"codeBlock","attrs":{"language":"java"},
                  "content":[{"type":"text","text":"int x = 1;",
                   "marks":[{"type":"strong"}]}]}]}""");

        JsonNode block = result.path("content").get(0);
        assertThat(block.path("attrs").path("language").asText()).isEqualTo("java");
        assertThat(block.path("content").get(0).has("marks")).isFalse();
    }

    @Test
    @DisplayName("only a doc counts as a document")
    void nonDocumentsAreNotMistakenForOne() throws Exception {
        assertThat(AdfSanitizer.isDocument(JSON.readTree("{\"type\":\"paragraph\"}"))).isFalse();
        assertThat(AdfSanitizer.isDocument(JSON.readTree("[]"))).isFalse();
        assertThat(AdfSanitizer.isDocument(JSON.readTree("{\"type\":\"doc\"}"))).isTrue();
    }

    private static JsonNode sanitize(String document) throws Exception {
        return AdfSanitizer.sanitize(JSON.readTree(document));
    }

    private static String text(JsonNode node) {
        if (node.has("text")) {
            return node.path("text").asText();
        }
        var collected = new StringBuilder();
        node.path("content").forEach(child -> collected.append(text(child)));
        return collected.toString();
    }
}
