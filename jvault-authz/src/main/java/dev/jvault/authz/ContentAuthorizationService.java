package dev.jvault.authz;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a principal may do something with externally stored content.
 *
 * <p>Called on <strong>every</strong> content request — metadata, preview, thumbnail, full
 * download, each range request, version list, historical version, export, and the replay of a
 * quarantined message. There is no session-level "already authorized for this object", because
 * that is exactly how a revoked grant keeps working until someone reloads the page
 * (docs/11-authorization.md 11.5).
 *
 * <p><strong>Possession of a link grants nothing.</strong> The link names content; it does not
 * carry authority. No signed URLs, no capability tokens, no presigned storage links.
 *
 * <p>Pure decision logic: the ACL and the Jira check are ports, the clock is injected, and there
 * is no cache inside. Caching belongs to the caller, where its TTL is a visible policy decision
 * rather than a hidden one.
 */
public final class ContentAuthorizationService {

    private final AclRepository acl;
    private final JiraAccessChecker jira;
    private final SpaceSettings spaces;
    private final Clock clock;

    public ContentAuthorizationService(AclRepository acl,
                                       JiraAccessChecker jira,
                                       SpaceSettings spaces,
                                       Clock clock) {
        this.acl = Objects.requireNonNull(acl, "acl");
        this.jira = Objects.requireNonNull(jira, "jira");
        this.spaces = Objects.requireNonNull(spaces, "spaces");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param subject the caller's own principal plus every group and role they hold. Resolved per
     *                request from current identity-provider state, never frozen into a session —
     *                removing someone from a group must take effect without them logging out
     */
    public AuthorizationDecision authorize(Subject subject, Scope scope, Permission permission) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(permission, "permission");

        Scope space = scope.spaceScope();
        SpacePermissionMode mode = spaces.modeOf(space.id());
        Set<Permission> effective = effectivePermissions(subject, scope);

        boolean vaultAllows = effective.contains(permission);

        if (!mode.requiresJiraCheck()) {
            return vaultAllows
                    ? AuthorizationDecision.allow(AuthorizationDecision.GRANTED, effective)
                    : AuthorizationDecision.deny(AuthorizationDecision.NO_VAULT_GRANT, effective);
        }

        // Short-circuit only where it cannot change the answer. Under INTERSECT a missing vault
        // grant is already decisive, and asking Jira anyway would spend a request — and a slice
        // of a rate-limit budget user-facing work needs — to learn nothing.
        if (mode == SpacePermissionMode.INTERSECT && !vaultAllows) {
            return AuthorizationDecision.deny(AuthorizationDecision.NO_VAULT_GRANT, effective);
        }

        JiraAccessChecker.Access jiraAccess = jira.canBrowse(subject.principal(), scope);

        return switch (jiraAccess) {
            case ALLOWED -> switch (mode) {
                case INTERSECT -> vaultAllows
                        ? AuthorizationDecision.allow(AuthorizationDecision.GRANTED, effective)
                        : AuthorizationDecision.deny(AuthorizationDecision.NO_VAULT_GRANT, effective);
                case JIRA_ONLY -> AuthorizationDecision.allow(
                        AuthorizationDecision.GRANTED_BY_JIRA, effective);
                case UNION -> AuthorizationDecision.allow(
                        vaultAllows ? AuthorizationDecision.GRANTED
                                : AuthorizationDecision.GRANTED_BY_JIRA, effective);
                case VAULT_ONLY -> throw new IllegalStateException("unreachable");
            };

            case DENIED -> AuthorizationDecision.deny(AuthorizationDecision.JIRA_NO_BROWSE, effective);

            case NOT_CONNECTED -> mode == SpacePermissionMode.UNION && vaultAllows
                    ? AuthorizationDecision.allow(AuthorizationDecision.GRANTED, effective)
                    // Not a denial of permission but a missing prerequisite, and the caller turns
                    // this into a "Connect Jira" prompt rather than an access-denied page.
                    : AuthorizationDecision.deny(AuthorizationDecision.JIRA_NOT_CONNECTED, effective);

            case UNAVAILABLE -> degraded(subject, scope, permission, mode, effective, vaultAllows);
        };
    }

    /**
     * Jira could not be reached, so {@code INTERSECT} cannot be evaluated.
     *
     * <p>Fail closed by default. That is correct and it is harsh: a security team locked out of
     * incident evidence because Jira is down is a real operational risk, and incident response is
     * exactly when this content is needed. So a space may allow a bounded window in which a
     * <em>previously granted</em> decision is reused — never a manufactured one, never for writes,
     * and always audited as {@code DEGRADED_ALLOW} (docs/11-authorization.md 11.6).
     */
    private AuthorizationDecision degraded(Subject subject,
                                           Scope scope,
                                           Permission permission,
                                           SpacePermissionMode mode,
                                           Set<Permission> effective,
                                           boolean vaultAllows) {
        Duration grace = spaces.degradedGrace(scope.spaceScope().id());

        if (grace.isZero() || grace.isNegative()) {
            return AuthorizationDecision.unavailable(AuthorizationDecision.JIRA_UNAVAILABLE);
        }
        if (!permission.equals(Permission.VIEW) && !permission.equals(Permission.DOWNLOAD)) {
            // A grace window covers reading evidence during an outage. It does not cover writing,
            // where acting on a stale permission changes state that is awkward to unwind.
            return AuthorizationDecision.unavailable(AuthorizationDecision.JIRA_UNAVAILABLE);
        }
        if (mode == SpacePermissionMode.JIRA_ONLY || !vaultAllows) {
            // Nothing to fall back on: without a vault grant there is no previously established
            // allow to reuse, and inventing one is exactly what must not happen.
            return AuthorizationDecision.unavailable(AuthorizationDecision.JIRA_UNAVAILABLE);
        }

        Instant since = clock.instant().minus(grace);
        return jira.lastKnownGoodAllow(subject.principal(), scope, since)
                ? AuthorizationDecision.allow(AuthorizationDecision.DEGRADED_ALLOW, effective)
                : AuthorizationDecision.unavailable(AuthorizationDecision.JIRA_UNAVAILABLE);
    }

    /**
     * The permissions a subject holds at a scope: the union of every active grant reaching it.
     *
     * <p>Walks from the scope upward, stopping at an inheritance break. There are deliberately no
     * explicit denies — a deny needs an evaluation order, and evaluation order makes "why can this
     * person see this?" unanswerable. The tool for restricting a subtree is a break, not a deny
     * (docs/11-authorization.md 11.2).
     */
    public Set<Permission> effectivePermissions(Subject subject, Scope scope) {
        Instant now = clock.instant();
        var effective = EnumSet.noneOf(Permission.class);

        for (Scope at : scope.chain()) {
            for (Grant grant : acl.grantsAt(at)) {
                if (grant.isActive(now) && subject.matches(grant.principal())) {
                    effective.addAll(grant.permissions());
                }
            }
            if (acl.hasInheritanceBreak(at)) {
                // Grants above this node do not reach it. Used for the genuinely exceptional part
                // inside an otherwise open ticket.
                break;
            }
        }
        return Set.copyOf(effective);
    }

    /**
     * Whether the caller may even learn that this content exists.
     *
     * <p>Callers use this to choose between 404 and 403: someone without access to the containing
     * space gets "not found", so that probing content references cannot confirm what exists.
     * Once the space is visible, a clear 403 is more useful than a confusing 404.
     */
    public boolean canRevealExistence(Subject subject, Scope scope) {
        return !effectivePermissions(subject, scope.spaceScope()).isEmpty();
    }

    /** A caller and every principal they hold, resolved fresh per request. */
    public record Subject(Principal principal, Set<Principal> groupsAndRoles) {

        public Subject {
            Objects.requireNonNull(principal, "principal");
            groupsAndRoles = groupsAndRoles == null ? Set.of() : Set.copyOf(groupsAndRoles);
        }

        public static Subject of(Principal principal, Principal... groupsAndRoles) {
            return new Subject(principal, Set.of(groupsAndRoles));
        }

        boolean matches(Principal grantee) {
            return principal.equals(grantee) || groupsAndRoles.contains(grantee);
        }

        /** Every principal a grant could name to reach this subject. */
        public Set<Principal> allPrincipals() {
            var all = new java.util.HashSet<>(groupsAndRoles);
            all.add(principal);
            return Set.copyOf(all);
        }
    }

    /** Per-space configuration the decision depends on. */
    public interface SpaceSettings {

        SpacePermissionMode modeOf(String spaceId);

        /** How long a last-known-good decision may be reused while Jira is unreachable. */
        Duration degradedGrace(String spaceId);
    }

    /** Reads grants. Implementations must return only grants stored at exactly the given scope. */
    public interface AclRepository {

        List<Grant> grantsAt(Scope scope);

        boolean hasInheritanceBreak(Scope scope);
    }

    /**
     * Asks Jira whether this user may browse the issue behind a scope.
     *
     * <p>The check must be made <strong>as the requesting user</strong>, with their own Jira
     * authorization — never with the integration identity, which would defeat the point entirely
     * by answering "can the service account see it?".
     */
    public interface JiraAccessChecker {

        enum Access {
            ALLOWED,
            DENIED,
            /** The user has not connected Jira, so the question cannot be asked yet. */
            NOT_CONNECTED,
            /** Jira could not be reached. */
            UNAVAILABLE
        }

        Access canBrowse(Principal user, Scope scope);

        /**
         * Whether this user was allowed by Jira at some point since {@code since}.
         *
         * <p>Consulted only inside a space's configured grace window, and only to reuse an allow
         * that genuinely happened.
         */
        boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since);
    }
}
