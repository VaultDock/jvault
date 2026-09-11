package dev.jvault.domain.placement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic that makes FR-CP-2's "ties are impossible" a property rather than a promise.
 */
class PolicySelectorSpecificityTest {

    @Test
    @DisplayName("every distinct selector shape has a distinct specificity")
    void allThirtyTwoShapesAreDistinct() {
        Map<Integer, String> byScore = new HashMap<>();

        // All 2^5 combinations of pinned/unpinned components.
        for (int mask = 0; mask < 32; mask++) {
            var selector = new PolicySelector(
                    (mask & 1) != 0 ? "dep" : null,
                    (mask & 2) != 0 ? "SEC" : null,
                    (mask & 4) != 0 ? "10004" : null,
                    (mask & 8) != 0 ? PartType.DESCRIPTION : null,
                    (mask & 16) != 0 ? "customfield_10010" : null);

            String shape = Integer.toBinaryString(mask);
            String clash = byScore.put(selector.specificity(), shape);

            assertThat(clash)
                    .as("shapes %s and %s both scored %d — the weight scheme is no longer "
                            + "collision-free and PlacementResolver needs a tie-break",
                            clash, shape, selector.specificity())
                    .isNull();
        }
        assertThat(byScore).hasSize(32);
    }

    @Test
    @DisplayName("a pinned field beats every combination of coarser components")
    void fieldOutranksEverythingElseCombined() {
        int fieldOnly = new PolicySelector(null, null, null, null, "customfield_10250").specificity();
        int everythingElse = new PolicySelector("dep", "SEC", "10004", PartType.DESCRIPTION, null)
                .specificity();

        assertThat(fieldOnly).isGreaterThan(everythingElse);
    }

    @Test
    @DisplayName("the global default scores zero and matches everything")
    void globalDefaultMatchesAnything() {
        var any = PolicySelector.any();
        assertThat(any.specificity()).isZero();
        assertThat(any.matches(ctx("dep", "SEC", "10004", PartType.SUMMARY, null))).isTrue();
        assertThat(any.describe()).isEqualTo("(global default)");
    }

    @Test
    @DisplayName("a pinned component does not match a context where that component is absent")
    void pinnedFieldDoesNotMatchNullContextField() {
        var selector = new PolicySelector(null, null, null, null, "description");

        assertThat(selector.matches(ctx("dep", "SEC", "10004", PartType.DESCRIPTION, "description")))
                .isTrue();
        // A comment has no field key; a field-pinned policy must not capture it.
        assertThat(selector.matches(ctx("dep", "SEC", "10004", PartType.COMMENT, null)))
                .isFalse();
    }

    @Test
    @DisplayName("blank selector components are normalised to 'any'")
    void blankIsTreatedAsAny() {
        var selector = new PolicySelector("  ", "", null, null, " ");
        assertThat(selector.specificity()).isZero();
    }

    private static PlacementContext ctx(String dep, String project, String issueType,
                                        PartType partType, String field) {
        return new PlacementContext(dep, project, issueType, partType, field);
    }
}
