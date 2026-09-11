package dev.jvault.authz;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One principal's permissions on one scope.
 *
 * @param grantedByGrant the grant whose authority this one was made under, or {@code null} for a
 *                       grant made by a space administrator. Recording provenance is what makes
 *                       revocation cascade: withdrawing a delegation withdraws everything granted
 *                       beneath it, which is otherwise impossible to do reliably by hand
 * @param canDelegate    whether the holder may grant these permissions onward. Off by default:
 *                       delegation should be a decision, not an accident
 * @param expiresAt      when the grant lapses, or {@code null} for no expiry. Time-boxed access
 *                       that nobody has to remember to remove is the only kind that reliably ends
 */
public record Grant(UUID id,
                    Scope scope,
                    Principal principal,
                    Set<Permission> permissions,
                    boolean canDelegate,
                    int delegationDepth,
                    UUID grantedByGrant,
                    Principal grantedBy,
                    Instant expiresAt,
                    Instant createdAt) {

    public Grant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(principal, "principal");
        permissions = Set.copyOf(permissions);
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("a grant with no permissions grants nothing; "
                    + "delete it instead of storing it");
        }
    }

    public static Grant of(Scope scope, Principal principal, Set<Permission> permissions) {
        return new Grant(UUID.randomUUID(), scope, principal, permissions, false, 0, null,
                null, null, Instant.EPOCH);
    }

    public boolean isActive(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public Grant expiringAt(Instant when) {
        return new Grant(id, scope, principal, permissions, canDelegate, delegationDepth,
                grantedByGrant, grantedBy, when, createdAt);
    }

    public Grant delegable() {
        return new Grant(id, scope, principal, permissions, true, delegationDepth,
                grantedByGrant, grantedBy, expiresAt, createdAt);
    }

    @Override
    public String toString() {
        return principal + " -> " + permissions + " on " + scope
                + (expiresAt == null ? "" : " until " + expiresAt);
    }
}
