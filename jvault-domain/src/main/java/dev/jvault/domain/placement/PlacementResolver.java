package dev.jvault.domain.placement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves which policy applies to a context, and applies request-level overrides.
 *
 * <p>A pure function of {@code (context, policySet, requestedOverride)}. No I/O, no clock, no
 * randomness, no Spring — so FR-CP-2's precedence guarantee can be covered by an exhaustive
 * table-driven test rather than argued about.
 */
public final class PlacementResolver {

    private PlacementResolver() {
    }

    public static ResolvedPlacement resolve(PlacementContext context, PolicySet policies) {
        return resolve(context, policies, null);
    }

    /**
     * @param requestedOverride placement requested by the caller — a REST client, a Kafka
     *                          mapping, a user in the UI. Applied only when the winning policy
     *                          sets {@code allowOverride}, and only when it is
     *                          <em>more protective</em> than the policy's placement. Loosening
     *                          protection is an administrator action, never a request one
     *                          (docs/05-content-placement.md 5.1).
     */
    public static ResolvedPlacement resolve(PlacementContext context,
                                            PolicySet policies,
                                            Placement requestedOverride) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policies, "policies");

        PlacementPolicy winner = winningPolicy(context, policies);

        Placement placement = winner.placement();
        boolean overridden = false;

        if (requestedOverride != null && requestedOverride != placement) {
            Placement tightened = Placement.moreProtective(placement, requestedOverride);
            // Honour an override only when the policy permits one AND it tightens. A request
            // that would loosen protection is ignored rather than rejected: the caller gets the
            // safe outcome, and the attempt is worth auditing at the call site.
            if (winner.allowOverride() && tightened == requestedOverride) {
                placement = tightened;
                overridden = true;
            }
        }

        return new ResolvedPlacement(
                context,
                placement,
                winner.classification(),
                winner.storageRoute(),
                winner.keyRing(),
                winner.encryptionRequired(),
                winner.surrogate(),
                winner.linkPlacements(),
                winner.allowOverride(),
                winner.id(),
                overridden);
    }

    /**
     * The single most specific matching policy, or the global default.
     *
     * <p>No tie-break is needed: selector weights are distinct powers of two, so distinct
     * selector shapes have distinct specificity, and two selectors of the same shape can never
     * both match one context. See {@link PolicySelector}.
     */
    static PlacementPolicy winningPolicy(PlacementContext context, PolicySet policies) {
        List<PlacementPolicy> matches = policies.matching(context);
        if (matches.isEmpty()) {
            return PolicySet.GLOBAL_DEFAULT;
        }
        assertNoTie(matches);
        return matches.get(0);
    }

    /** The explanation shown in the admin UI: every matching policy, most specific first. */
    public static List<PlacementPolicy> explain(PlacementContext context, PolicySet policies) {
        return policies.matching(context);
    }

    public static Optional<PlacementPolicy> mostSpecific(PlacementContext context, PolicySet policies) {
        return policies.matching(context).stream().findFirst();
    }

    private static void assertNoTie(List<PlacementPolicy> matches) {
        if (matches.size() > 1 && matches.get(0).specificity() == matches.get(1).specificity()) {
            // Unreachable given the weight scheme and PolicySet's uniqueness check. If it ever
            // fires, the weight scheme has been changed without re-reading PolicySelector's
            // javadoc, and failing loudly beats silently picking one at random.
            throw new IllegalStateException(
                    "ambiguous placement policy: '" + matches.get(0).id() + "' and '"
                            + matches.get(1).id() + "' both match at specificity "
                            + matches.get(0).specificity());
        }
    }
}
