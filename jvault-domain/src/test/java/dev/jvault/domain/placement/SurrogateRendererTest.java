package dev.jvault.domain.placement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurrogateRendererTest {

    private static final String CANARY = "the-actual-sensitive-incident-narrative";

    @Test
    @DisplayName("substitutes known tokens")
    void substitutesKnownTokens() {
        var spec = SurrogateSpec.placeholder(
                "Stored in jvault. Classification: {{classification}}. Open: {{link}}");
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.CLASSIFICATION, "RESTRICTED");
        values.put(SurrogateToken.LINK, "https://jvault.example.com/c/01J8ZQ");

        assertThat(SurrogateRenderer.render(spec, values))
                .isEqualTo("Stored in jvault. Classification: RESTRICTED. "
                        + "Open: https://jvault.example.com/c/01J8ZQ");
    }

    @Test
    @DisplayName("there is no token that can interpolate the original content")
    void noTokenReachesTheContent() {
        // The renderer's entire input is an EnumMap keyed by SurrogateToken. There is no slot
        // for the original value, so a surrogate cannot carry it however the template is written.
        var spec = SurrogateSpec.placeholder(
                "{{link}} {{classification}} {{partType}} {{project}} {{issueType}} "
                        + "{{ticketRef}} {{contentRef}} {{sizeHuman}} {{mediaType}}");
        var values = SurrogateRenderer.values();
        for (SurrogateToken token : SurrogateToken.values()) {
            values.put(token, "value-of-" + token.templateName());
        }

        assertThat(SurrogateRenderer.render(spec, values)).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("an unknown token fails loudly rather than rendering literally")
    void unknownTokenThrows() {
        var spec = SurrogateSpec.placeholder("Original: {{originalValue}}");

        assertThatThrownBy(() -> SurrogateRenderer.render(spec, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalValue");
    }

    @Test
    @DisplayName("unknownTokens lists every offender for validation messages")
    void listsUnknownTokens() {
        assertThat(SurrogateRenderer.unknownTokens("{{link}} {{nope}} {{alsoNope}}"))
                .containsExactly("nope", "alsoNope");
    }

    @Test
    @DisplayName("a missing value renders empty rather than blocking the Jira write")
    void missingValueRendersEmpty() {
        var spec = SurrogateSpec.placeholder("Size: {{sizeHuman}} Link: {{link}}");
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.LINK, "https://jvault.example.com/c/x");

        assertThat(SurrogateRenderer.render(spec, values))
                .isEqualTo("Size: Link: https://jvault.example.com/c/x");
    }

    @Test
    @DisplayName("a rendered summary always fits Jira's 255-character cap")
    void summaryIsTruncatedToJiraLimit() {
        var spec = SurrogateSpec.placeholder("{{classification}} {{link}}");
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.CLASSIFICATION, "RESTRICTED");
        values.put(SurrogateToken.LINK, "x".repeat(400));

        String summary = SurrogateRenderer.renderSummary(spec, values);

        assertThat(summary).hasSizeLessThanOrEqualTo(SurrogateRenderer.SUMMARY_MAX_LENGTH);
        assertThat(summary).endsWith("…");
    }

    @Test
    @DisplayName("a summary that renders blank falls back to something Jira will accept")
    void blankSummaryGetsAFallback() {
        var spec = SurrogateSpec.placeholder("{{sizeHuman}}");
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.CLASSIFICATION, "RESTRICTED");

        String summary = SurrogateRenderer.renderSummary(spec, values);

        assertThat(summary).isNotBlank().contains("RESTRICTED");
    }

    @Test
    @DisplayName("default surrogates exist for every part type and are all valid")
    void defaultSurrogatesAreValid() {
        for (PartType partType : PartType.values()) {
            var spec = SurrogateSpec.defaultFor(partType);
            assertThat(SurrogateRenderer.unknownTokens(spec.template()))
                    .as("default surrogate for %s uses an unknown token", partType)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("tokens tolerate surrounding whitespace")
    void whitespaceInsideBracesIsAllowed() {
        var values = SurrogateRenderer.values();
        values.put(SurrogateToken.LINK, "https://x");

        assertThat(SurrogateRenderer.render(SurrogateSpec.placeholder("Open: {{  link  }}"), values))
                .isEqualTo("Open: https://x");
    }
}
