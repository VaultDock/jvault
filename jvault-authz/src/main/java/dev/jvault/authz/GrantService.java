package dev.jvault.authz;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Makes and revokes grants, under the rule that nobody can give away more than they hold.
 *
 * <p>Privilege escalation here would be quiet and total: a user with {@code VIEW} who could grant
 * {@code DOWNLOAD} to a group they belong to has just given themselves {@code DOWNLOAD}. So the
 * subset rule is enforced at grant time, server-side, and covered by a generative test rather than
 * by review (docs/11-authorization.md 11.4).
 */
public final class GrantService {

    /** How deep a chain of delegated management may go before it stops being auditable. */
    public static final int MAX_DELEGATION_DEPTH = 2;

    private final ContentAuthorizationService authorization;
    private final MutableAclRepository acl;
    private final Clock clock;

    public GrantService(ContentAuthorizationService authorization,
                        MutableAclRepository acl,
                        Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.acl = Objects.requireNonNull(acl, "acl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @throws GrantRefusedException when the grantor lacks the authority. The message names codes
     *                               and principals, never content
     */
    public Grant grant(ContentAuthorizationService.Subject grantor,
                       Scope scope,
                       Principal grantee,
                       Set<Permission> permissions,
                       boolean canDelegate) {

        Set<Permission> grantorHolds = authorization.effectivePermissions(grantor, scope);

        if (!grantorHolds.contains(Permission.MANAGE_ACCESS)) {
            throw new GrantRefusedException("NO_MANAGE_ACCESS",
                    grantor.principal() + " may not manage access on " + scope);
        }

        // The rule that matters. A grantor may pass on only what they themselves hold.
        if (!grantorHolds.containsAll(permissions)) {
            var excess = new java.util.HashSet<>(permissions);
            excess.removeAll(grantorHolds);
            throw new GrantRefusedException("GRANT_EXCEEDS_AUTHORITY",
                    grantor.principal() + " does not hold " + excess + " on " + scope);
        }

        Grant authority = delegatingGrant(grantor, scope);
        int depth = authority == null ? 0 : authority.delegationDepth() + 1;

        // canDelegate governs passing authority *onward* from something you were given, not the
        // root authority an administrator holds directly. A space administrator whose configured
        // grant happened to have the flag off could otherwise grant nothing at all, which is not
        // what "delegation is off by default" was ever meant to mean.
        boolean authorityWasItselfDelegated = authority != null && authority.grantedByGrant() != null;
        if (authorityWasItselfDelegated && !authority.canDelegate()) {
            throw new GrantRefusedException("DELEGATION_NOT_PERMITTED",
                    "the grant " + grantor.principal() + " holds on " + scope
                            + " was delegated and does not permit delegating onward");
        }
        if (depth > MAX_DELEGATION_DEPTH) {
            throw new GrantRefusedException("DELEGATION_TOO_DEEP",
                    "delegation beyond depth " + MAX_DELEGATION_DEPTH
                            + " stops being auditable");
        }
        if (permissions.contains(Permission.MANAGE_ACCESS) && !canDelegate && authority != null) {
            // Passing on MANAGE_ACCESS without the right to delegate onward is allowed; passing it
            // on from a delegated grant that itself may not delegate is not, and is caught above.
            depth = Math.max(depth, 1);
        }

        var grant = new Grant(UUID.randomUUID(), scope, grantee, permissions, canDelegate, depth,
                authority == null ? null : authority.id(), grantor.principal(), null,
                clock.instant());

        acl.save(grant);
        return grant;
    }

    /**
     * Revokes a grant and everything granted under its authority.
     *
     * <p>Cascading is the point. Revoking a delegation while leaving the grants it produced in
     * place is the most common way access outlives the reason for it, and it is invisible in every
     * report that looks only at direct grants.
     *
     * @return every grant removed, so the audit record names all of them
     */
    public List<Grant> revoke(ContentAuthorizationService.Subject revoker, UUID grantId) {
        Grant target = acl.find(grantId).orElseThrow(
                () -> new GrantRefusedException("GRANT_NOT_FOUND", "no grant " + grantId));

        Set<Permission> revokerHolds = authorization.effectivePermissions(revoker, target.scope());
        if (!revokerHolds.contains(Permission.MANAGE_ACCESS)) {
            throw new GrantRefusedException("NO_MANAGE_ACCESS",
                    revoker.principal() + " may not manage access on " + target.scope());
        }

        var removed = new ArrayList<Grant>();
        removeCascading(target, removed);
        return List.copyOf(removed);
    }

    private void removeCascading(Grant grant, List<Grant> removed) {
        for (Grant child : acl.grantsMadeUnder(grant.id())) {
            removeCascading(child, removed);
        }
        acl.delete(grant.id());
        removed.add(grant);
    }

    /** The grant that gives this subject its management authority at a scope, if any. */
    private Grant delegatingGrant(ContentAuthorizationService.Subject grantor, Scope scope) {
        Grant best = null;
        for (Scope at : scope.chain()) {
            for (Grant grant : acl.grantsAt(at)) {
                if (grantor.matches(grant.principal())
                        && grant.permissions().contains(Permission.MANAGE_ACCESS)
                        && grant.isActive(clock.instant())) {
                    // Prefer the shallowest authority: a space administrator's own grant has
                    // depth 0 and is what a direct administrator should be acting under.
                    if (best == null || grant.delegationDepth() < best.delegationDepth()) {
                        best = grant;
                    }
                }
            }
        }
        return best;
    }

    /** Read-write ACL access. Kept separate from the read port the decision path uses. */
    public interface MutableAclRepository extends ContentAuthorizationService.AclRepository {

        void save(Grant grant);

        void delete(UUID grantId);

        java.util.Optional<Grant> find(UUID grantId);

        /** Grants made under a given grant's authority, for cascading revocation. */
        List<Grant> grantsMadeUnder(UUID grantId);
    }

    /** Refused, with a stable code an API can map and a dashboard can count. */
    public static class GrantRefusedException extends RuntimeException {

        private final String code;

        public GrantRefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
