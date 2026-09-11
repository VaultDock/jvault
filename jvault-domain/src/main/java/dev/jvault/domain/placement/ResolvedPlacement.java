package dev.jvault.domain.placement;

import dev.jvault.domain.common.Classification;

import java.util.Objects;
import java.util.Set;

/**
 * The outcome of resolving policy for one context: everything downstream needs to know about
 * where this part goes and how it is protected.
 *
 * @param sourcePolicyId id of the policy that won, or {@code "global-default"}
 * @param overridden     whether a request-level override tightened the placement
 */
public record ResolvedPlacement(
        PlacementContext context,
        Placement placement,
        Classification classification,
        String storageRoute,
        String keyRing,
        boolean encryptionRequired,
        SurrogateSpec surrogate,
        Set<LinkPlacement> linkPlacements,
        boolean allowOverride,
        String sourcePolicyId,
        boolean overridden) {

    public ResolvedPlacement {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(sourcePolicyId, "sourcePolicyId");
        linkPlacements = linkPlacements == null ? Set.of() : Set.copyOf(linkPlacements);
    }

    public boolean isExternallyStored() {
        return placement.isExternallyStored();
    }

    /** The surrogate to use, falling back to the default for the part type. */
    public SurrogateSpec effectiveSurrogate() {
        return surrogate != null ? surrogate : SurrogateSpec.defaultFor(context.partType());
    }
}
