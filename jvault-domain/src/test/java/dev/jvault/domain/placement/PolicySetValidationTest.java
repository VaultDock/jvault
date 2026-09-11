package dev.jvault.domain.placement;

import dev.jvault.domain.common.Classification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Configuration is rejected on save, not discovered at ticket-creation time. A policy that
 * cannot be honoured should never become active.
 */
class PolicySetValidationTest {

    @Test
    @DisplayName("two policies sharing a selector are rejected — this is what makes ties impossible")
    void duplicateSelectorsAreRejected() {
        var selector = new PolicySelector("dep", "SEC", null, PartType.DESCRIPTION, null);
        var a = validExternal("a").selector(selector).build();
        var b = validExternal("b").selector(selector).build();

        assertThatThrownBy(() -> PolicySet.of(List.of(a, b)))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("share the selector")
                .hasMessageContaining("project=SEC");
    }

    @Test
    @DisplayName("duplicate policy ids are rejected")
    void duplicateIdsAreRejected() {
        var a = validExternal("same-id").selector(new PolicySelector("dep", "SEC", null, null, null)).build();
        var b = validExternal("same-id").selector(new PolicySelector("dep", "ENG", null, null, null)).build();

        assertThat(PolicySet.validate(List.of(a, b)))
                .anyMatch(p -> p.contains("duplicate policy id"));
    }

    @Test
    @DisplayName("externalising content without a storage route is rejected")
    void externalWithoutStorageRouteIsRejected() {
        var policy = validExternal("no-route").storageRoute(null).build();

        assertThat(PolicySet.validate(List.of(policy)))
                .anyMatch(p -> p.contains("names no storageRoute"));
    }

    @Test
    @DisplayName("externalising content with no link placement is rejected as unreachable")
    void externalWithoutLinkPlacementIsRejected() {
        var policy = validExternal("no-link").linkPlacements(Set.of()).build();

        assertThat(PolicySet.validate(List.of(policy)))
                .anyMatch(p -> p.contains("would be unreachable from Jira"));
    }

    @Test
    @DisplayName("requiring encryption without a key ring is rejected")
    void encryptionWithoutKeyRingIsRejected() {
        var policy = validExternal("no-ring").keyRing(null).build();

        assertThat(PolicySet.validate(List.of(policy)))
                .anyMatch(p -> p.contains("names no keyRing"));
    }

    @Test
    @DisplayName("an unknown surrogate token is caught at configuration time, not at render time")
    void unknownSurrogateTokenIsRejected() {
        var policy = validExternal("bad-token")
                .surrogate(SurrogateSpec.placeholder("Original was: {{originalValue}} — {{link}}"))
                .build();

        assertThat(PolicySet.validate(List.of(policy)))
                .anyMatch(p -> p.contains("unknown surrogate token '{{originalValue}}'"));
    }

    @Test
    @DisplayName("DERIVED_SUMMARY is refused until a classifier gate exists")
    void derivedSummaryIsRefused() {
        var policy = validExternal("derived")
                .surrogate(new SurrogateSpec(SurrogateKind.DERIVED_SUMMARY, "{{link}}"))
                .build();

        assertThat(PolicySet.validate(List.of(policy)))
                .anyMatch(p -> p.contains("DERIVED_SUMMARY"));
    }

    @Test
    @DisplayName("SPLIT on an attachment is refused: byte streams have no sections")
    void splitOnAttachmentIsRejected() {
        var policy = validExternal("split-attachment")
                .selector(new PolicySelector("dep", "SEC", null, PartType.ATTACHMENT, null))
                .placement(Placement.SPLIT)
                .build();

        assertThat(PolicySet.validate(List.of(policy)))
                .anyMatch(p -> p.contains("have no sections to split"));
    }

    @Test
    @DisplayName("every problem is reported at once, not just the first")
    void allProblemsAreReported() {
        var policy = PlacementPolicy.builder()
                .id("broken")
                .selector(new PolicySelector("dep", "SEC", null, PartType.DESCRIPTION, null))
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .encryptionRequired(true)
                .build();

        assertThat(PolicySet.validate(List.of(policy))).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("a policy that keeps content in Jira needs none of the external machinery")
    void jiraPlacementNeedsNoRoute() {
        var policy = PlacementPolicy.builder()
                .id("stays-in-jira")
                .selector(new PolicySelector("dep", "ENG", null, null, null))
                .placement(Placement.JIRA)
                .classification(Classification.INTERNAL)
                .build();

        assertThatCode(() -> PolicySet.of(List.of(policy))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate() returns problems rather than throwing, for the admin API")
    void validateReturnsProblems() {
        assertThat(PolicySet.validate(List.of())).isEmpty();
    }

    private static PlacementPolicy.Builder validExternal(String id) {
        return PlacementPolicy.builder()
                .id(id)
                .selector(new PolicySelector("dep", "SEC", null, PartType.DESCRIPTION, null))
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .storageRoute("obj-dc1-restricted")
                .keyRing("sec-restricted")
                .encryptionRequired(true)
                .surrogate(SurrogateSpec.placeholder("Stored in jvault: {{link}}"))
                .linkPlacements(Set.of(LinkPlacement.REMOTE_LINK));
    }
}
