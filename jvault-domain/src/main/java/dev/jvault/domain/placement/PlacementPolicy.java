package dev.jvault.domain.placement;

import dev.jvault.domain.common.Classification;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One administrator-authored rule: for contexts matching {@code selector}, place content
 * {@code placement}, protect it at {@code classification}, store it via {@code storageRoute}.
 *
 * @param id             stable identifier, used in audit records and validation messages
 * @param selector       which contexts this applies to
 * @param placement      where the value lives
 * @param classification sensitivity of the content
 * @param storageRoute   named storage route; required when the placement is external
 * @param keyRing        named key ring; required when encryption is required
 * @param encryptionRequired whether application-level encryption is mandatory
 * @param surrogate      Jira-visible text; required when the placement is external
 * @param linkPlacements where the link is attached in Jira
 * @param allowOverride  whether a request may tighten the placement for this selector
 * @param enabled        disabled policies are ignored by the resolver but kept for history
 */
public record PlacementPolicy(
        String id,
        PolicySelector selector,
        Placement placement,
        Classification classification,
        String storageRoute,
        String keyRing,
        boolean encryptionRequired,
        SurrogateSpec surrogate,
        Set<LinkPlacement> linkPlacements,
        boolean allowOverride,
        boolean enabled) {

    public PlacementPolicy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(classification, "classification");
        linkPlacements = linkPlacements == null ? Set.of() : Set.copyOf(linkPlacements);
    }

    public int specificity() {
        return selector.specificity();
    }

    /**
     * Problems that make this policy unusable, as human-readable strings.
     *
     * <p>Validation is deliberately strict and happens at configuration time rather than at
     * ticket-creation time: a policy that cannot be honoured should be refused when an
     * administrator saves it, not discovered when it fails closed during an incident.
     */
    List<String> problems() {
        var problems = new java.util.ArrayList<String>();

        if (placement.isExternallyStored()) {
            if (storageRoute == null || storageRoute.isBlank()) {
                problems.add("policy '" + id + "' (" + selector.describe()
                        + ") places content externally but names no storageRoute");
            }
            if (surrogate == null) {
                problems.add("policy '" + id + "' (" + selector.describe()
                        + ") places content externally but names no surrogate");
            }
            if (linkPlacements.isEmpty()) {
                problems.add("policy '" + id + "' (" + selector.describe()
                        + ") places content externally but names no linkPlacement, so the"
                        + " content would be unreachable from Jira");
            }
        }

        if (encryptionRequired && (keyRing == null || keyRing.isBlank())) {
            problems.add("policy '" + id + "' requires encryption but names no keyRing");
        }

        if (surrogate != null && surrogate.kind() == SurrogateKind.DERIVED_SUMMARY) {
            problems.add("policy '" + id + "' requests a DERIVED_SUMMARY surrogate, which is not"
                    + " implemented; deriving Jira-visible text from content deemed too sensitive"
                    + " for Jira needs a classifier gate first (docs/05-content-placement.md 5.3)");
        }

        if (surrogate != null) {
            SurrogateRenderer.unknownTokens(surrogate.template()).forEach(token ->
                    problems.add("policy '" + id + "' uses unknown surrogate token '{{" + token
                            + "}}'; known tokens are: " + SurrogateToken.knownTokens()));
        }

        // SUMMARY is always required by Jira and capped at 255 characters, so an externalised
        // summary must still render a usable title (docs/05-content-placement.md 5.3).
        if (selector.partType() == PartType.SUMMARY
                && placement == Placement.EXTERNAL
                && surrogate == null) {
            problems.add("policy '" + id + "' externalises SUMMARY without a surrogate; Jira"
                    + " requires a summary on every issue");
        }

        if (placement == Placement.SPLIT && selector.partType() == PartType.ATTACHMENT) {
            problems.add("policy '" + id + "' requests SPLIT for ATTACHMENT; attachments are"
                    + " opaque byte streams and have no sections to split");
        }

        return problems;
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id).selector(selector).placement(placement).classification(classification)
                .storageRoute(storageRoute).keyRing(keyRing).encryptionRequired(encryptionRequired)
                .surrogate(surrogate).linkPlacements(linkPlacements)
                .allowOverride(allowOverride).enabled(enabled);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; policies have enough optional components to make constructors painful. */
    public static final class Builder {
        private String id;
        private PolicySelector selector = PolicySelector.any();
        private Placement placement = Placement.JIRA;
        private Classification classification = Classification.INTERNAL;
        private String storageRoute;
        private String keyRing;
        private boolean encryptionRequired;
        private SurrogateSpec surrogate;
        private Set<LinkPlacement> linkPlacements = Set.of();
        private boolean allowOverride;
        private boolean enabled = true;

        public Builder id(String v) { this.id = v; return this; }
        public Builder selector(PolicySelector v) { this.selector = v; return this; }
        public Builder placement(Placement v) { this.placement = v; return this; }
        public Builder classification(Classification v) { this.classification = v; return this; }
        public Builder storageRoute(String v) { this.storageRoute = v; return this; }
        public Builder keyRing(String v) { this.keyRing = v; return this; }
        public Builder encryptionRequired(boolean v) { this.encryptionRequired = v; return this; }
        public Builder surrogate(SurrogateSpec v) { this.surrogate = v; return this; }
        public Builder linkPlacements(Set<LinkPlacement> v) { this.linkPlacements = v; return this; }
        public Builder linkPlacements(LinkPlacement... v) { this.linkPlacements = Set.of(v); return this; }
        public Builder allowOverride(boolean v) { this.allowOverride = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }

        public PlacementPolicy build() {
            return new PlacementPolicy(id, selector, placement, classification, storageRoute,
                    keyRing, encryptionRequired, surrogate, linkPlacements, allowOverride, enabled);
        }
    }
}
