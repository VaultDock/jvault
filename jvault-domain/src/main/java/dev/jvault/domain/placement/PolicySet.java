package dev.jvault.domain.placement;

import dev.jvault.domain.common.Classification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A validated, immutable set of placement policies plus the global default.
 *
 * <p>Construction validates. An invalid set is never representable, so the resolver can be a
 * total function with no error paths — which is what lets it stay pure and exhaustively testable.
 *
 * <p>The uniqueness check on selectors mirrors the database's {@code UNIQUE} constraint over the
 * selector tuple (docs/04-data-model.md 4.5). Keeping it in both places is deliberate: the
 * database stops two administrators racing, and this stops a policy set assembled in memory —
 * from a file, a migration, a test — from having the same defect.
 */
public final class PolicySet {

    /** Shipped default: nothing leaves Jira unless an administrator opts a scope in. */
    public static final PlacementPolicy GLOBAL_DEFAULT = PlacementPolicy.builder()
            .id("global-default")
            .selector(PolicySelector.any())
            .placement(Placement.JIRA)
            .classification(Classification.INTERNAL)
            .allowOverride(false)
            .build();

    private final List<PlacementPolicy> enabledPolicies;
    private final List<PlacementPolicy> allPolicies;

    private PolicySet(List<PlacementPolicy> allPolicies) {
        this.allPolicies = List.copyOf(allPolicies);
        this.enabledPolicies = allPolicies.stream().filter(PlacementPolicy::enabled).toList();
    }

    /**
     * @throws PolicyValidationException listing every problem found
     */
    public static PolicySet of(Collection<PlacementPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        var problems = new ArrayList<String>();

        var bySelector = new HashMap<PolicySelector, PlacementPolicy>();
        var byId = new HashMap<String, PlacementPolicy>();

        for (PlacementPolicy policy : policies) {
            PlacementPolicy clashingSelector = bySelector.putIfAbsent(policy.selector(), policy);
            if (clashingSelector != null) {
                problems.add("policies '" + clashingSelector.id() + "' and '" + policy.id()
                        + "' share the selector [" + policy.selector().describe()
                        + "]; selectors must be unique so that resolution is unambiguous");
            }
            PlacementPolicy clashingId = byId.putIfAbsent(policy.id(), policy);
            if (clashingId != null) {
                problems.add("duplicate policy id '" + policy.id() + "'");
            }
            problems.addAll(policy.problems());
        }

        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
        return new PolicySet(List.copyOf(policies));
    }

    public static PolicySet empty() {
        return new PolicySet(List.of());
    }

    /** Every policy whose selector matches, most specific first. Used by the resolver and by
     *  the admin UI's "why did this resolve?" explanation. */
    public List<PlacementPolicy> matching(PlacementContext context) {
        return enabledPolicies.stream()
                .filter(p -> p.selector().matches(context))
                .sorted((a, b) -> Integer.compare(b.specificity(), a.specificity()))
                .toList();
    }

    public List<PlacementPolicy> all() {
        return allPolicies;
    }

    public int size() {
        return allPolicies.size();
    }

    /** Validates a candidate set without retaining it. Used by the admin API before saving. */
    public static List<String> validate(Collection<PlacementPolicy> policies) {
        try {
            of(policies);
            return List.of();
        } catch (PolicyValidationException e) {
            return e.problems();
        }
    }

    Map<PolicySelector, PlacementPolicy> indexBySelector() {
        var map = new HashMap<PolicySelector, PlacementPolicy>();
        enabledPolicies.forEach(p -> map.put(p.selector(), p));
        return Map.copyOf(map);
    }
}
