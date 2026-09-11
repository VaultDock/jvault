package dev.jvault.domain.placement;

import dev.jvault.domain.common.Classification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-CP-2: for any (project, issue type, field) triple exactly one policy resolves, and the
 * resolver is a pure function. This is the table-driven test the requirement asks for.
 */
class PlacementResolverPrecedenceTest {

    private static final String DEP = "jira-cloud-prod";

    // A policy set shaped like the worked example in docs/examples/placement-policies.yaml.
    private static final PlacementPolicy PROJECT_BASELINE = external("sec-project-default")
            .selector(new PolicySelector(DEP, "SEC", null, null, null))
            .placement(Placement.JIRA)
            .classification(Classification.CONFIDENTIAL)
            .allowOverride(true)
            .build();

    private static final PlacementPolicy INCIDENT_DESCRIPTION = external("sec-incident-description")
            .selector(new PolicySelector(DEP, "SEC", "10004", PartType.DESCRIPTION, null))
            .classification(Classification.RESTRICTED)
            .build();

    private static final PlacementPolicy INCIDENT_SUMMARY_STAYS = external("sec-incident-summary")
            .selector(new PolicySelector(DEP, "SEC", "10004", PartType.SUMMARY, null))
            .placement(Placement.JIRA)
            .classification(Classification.INTERNAL)
            .allowOverride(true)
            .build();

    private static final PlacementPolicy PII_FIELD = external("any-customer-pii-field")
            .selector(new PolicySelector(DEP, null, null, null, "customfield_10250"))
            .classification(Classification.RESTRICTED)
            .build();

    private static final PlacementPolicy DEPLOYMENT_WIDE_ATTACHMENTS = external("dep-attachments")
            .selector(new PolicySelector(DEP, null, null, PartType.ATTACHMENT, null))
            .classification(Classification.CONFIDENTIAL)
            .build();

    private static final PolicySet POLICIES = PolicySet.of(List.of(
            PROJECT_BASELINE, INCIDENT_DESCRIPTION, INCIDENT_SUMMARY_STAYS,
            PII_FIELD, DEPLOYMENT_WIDE_ATTACHMENTS));

    static Stream<Arguments> precedenceTable() {
        return Stream.of(
                Arguments.of("most specific policy wins over the project baseline",
                        ctx("SEC", "10004", PartType.DESCRIPTION, "description"),
                        "sec-incident-description", Placement.EXTERNAL),

                Arguments.of("a field-pinned policy outranks a more-qualified part-type policy",
                        ctx("SEC", "10004", PartType.CUSTOM_FIELD, "customfield_10250"),
                        "any-customer-pii-field", Placement.EXTERNAL),

                Arguments.of("part-type policy applies where nothing more specific matches",
                        ctx("ENG", "10001", PartType.ATTACHMENT, null),
                        "dep-attachments", Placement.EXTERNAL),

                Arguments.of("project baseline applies to parts with no dedicated policy",
                        ctx("SEC", "10004", PartType.BODY, "body"),
                        "sec-project-default", Placement.JIRA),

                Arguments.of("an issue-type policy can keep a part in Jira against a broader rule",
                        ctx("SEC", "10004", PartType.SUMMARY, "summary"),
                        "sec-incident-summary", Placement.JIRA),

                Arguments.of("a different issue type in the same project falls back to the baseline",
                        ctx("SEC", "10099", PartType.DESCRIPTION, "description"),
                        "sec-project-default", Placement.JIRA),

                Arguments.of("an unconfigured project falls through to the global default",
                        ctx("MKT", "10002", PartType.DESCRIPTION, "description"),
                        "global-default", Placement.JIRA)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("precedenceTable")
    void resolvesExactlyOnePolicy(String description,
                                  PlacementContext context,
                                  String expectedPolicyId,
                                  Placement expectedPlacement) {
        ResolvedPlacement resolved = PlacementResolver.resolve(context, POLICIES);

        assertThat(resolved.sourcePolicyId()).isEqualTo(expectedPolicyId);
        assertThat(resolved.placement()).isEqualTo(expectedPlacement);
    }

    @Test
    @DisplayName("no two matching policies ever share a specificity, for any context")
    void noContextProducesATie() {
        for (String project : List.of("SEC", "ENG", "MKT")) {
            for (String issueType : List.of("10004", "10099")) {
                for (PartType partType : PartType.values()) {
                    for (String field : new String[]{null, "description", "customfield_10250"}) {
                        var context = ctx(project, issueType, partType, field);
                        List<PlacementPolicy> matches = PlacementResolver.explain(context, POLICIES);

                        assertThat(matches.stream().map(PlacementPolicy::specificity))
                                .as("context %s produced a specificity tie", context)
                                .doesNotHaveDuplicates();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("resolution is pure: same inputs, same output, every time")
    void resolutionIsPure() {
        var context = ctx("SEC", "10004", PartType.DESCRIPTION, "description");

        ResolvedPlacement first = PlacementResolver.resolve(context, POLICIES);
        ResolvedPlacement second = PlacementResolver.resolve(context, POLICIES);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("disabled policies are ignored but not forgotten")
    void disabledPoliciesDoNotResolve() {
        var disabled = INCIDENT_DESCRIPTION.toBuilder().enabled(false).build();
        var set = PolicySet.of(List.of(PROJECT_BASELINE, disabled));

        var resolved = PlacementResolver.resolve(
                ctx("SEC", "10004", PartType.DESCRIPTION, "description"), set);

        assertThat(resolved.sourcePolicyId()).isEqualTo("sec-project-default");
        assertThat(set.all()).hasSize(2);
    }

    @Nested
    @DisplayName("request-level overrides")
    class Overrides {

        @Test
        @DisplayName("an override that tightens is honoured where the policy allows it")
        void tighteningOverrideIsHonoured() {
            var context = ctx("SEC", "10004", PartType.SUMMARY, "summary");

            var resolved = PlacementResolver.resolve(context, POLICIES, Placement.EXTERNAL);

            assertThat(resolved.placement()).isEqualTo(Placement.EXTERNAL);
            assertThat(resolved.overridden()).isTrue();
        }

        @Test
        @DisplayName("an override that loosens is ignored, even where overrides are allowed")
        void looseningOverrideIsIgnored() {
            var externalByPolicy = external("loose-test")
                    .selector(new PolicySelector(DEP, "OPS", null, null, null))
                    .allowOverride(true)
                    .build();
            var set = PolicySet.of(List.of(externalByPolicy));
            var context = ctx("OPS", "10004", PartType.DESCRIPTION, "description");

            var resolved = PlacementResolver.resolve(context, set, Placement.JIRA);

            assertThat(resolved.placement()).isEqualTo(Placement.EXTERNAL);
            assertThat(resolved.overridden()).isFalse();
        }

        @Test
        @DisplayName("an override is ignored entirely where the policy forbids overrides")
        void overrideIsIgnoredWhenPolicyForbidsIt() {
            var context = ctx("SEC", "10004", PartType.DESCRIPTION, "description");
            assertThat(INCIDENT_DESCRIPTION.allowOverride()).isFalse();

            var resolved = PlacementResolver.resolve(context, POLICIES, Placement.SPLIT);

            assertThat(resolved.placement()).isEqualTo(Placement.EXTERNAL);
            assertThat(resolved.overridden()).isFalse();
        }

        @Test
        @DisplayName("moreProtective orders JIRA < SPLIT < EXTERNAL")
        void protectivenessOrdering() {
            assertThat(Placement.moreProtective(Placement.JIRA, Placement.SPLIT))
                    .isEqualTo(Placement.SPLIT);
            assertThat(Placement.moreProtective(Placement.SPLIT, Placement.EXTERNAL))
                    .isEqualTo(Placement.EXTERNAL);
            assertThat(Placement.moreProtective(Placement.EXTERNAL, Placement.JIRA))
                    .isEqualTo(Placement.EXTERNAL);
        }
    }

    private static PlacementPolicy.Builder external(String id) {
        return PlacementPolicy.builder()
                .id(id)
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .storageRoute("obj-dc1-restricted")
                .keyRing("sec-restricted")
                .encryptionRequired(true)
                .surrogate(SurrogateSpec.placeholder("Stored in jvault: {{link}}"))
                .linkPlacements(Set.of(LinkPlacement.REMOTE_LINK));
    }

    private static PlacementContext ctx(String project, String issueType,
                                        PartType partType, String field) {
        return new PlacementContext(DEP, project, issueType, partType, field);
    }
}
