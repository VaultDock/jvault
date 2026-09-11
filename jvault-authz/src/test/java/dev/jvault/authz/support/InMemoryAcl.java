package dev.jvault.authz.support;

import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Grant;
import dev.jvault.authz.GrantService;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.SpacePermissionMode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory ports for the authorization tests. */
public final class InMemoryAcl implements GrantService.MutableAclRepository {

    private final Map<UUID, Grant> grants = new LinkedHashMap<>();
    private final Set<String> inheritanceBreaks = new HashSet<>();

    @Override
    public List<Grant> grantsAt(Scope scope) {
        return grants.values().stream()
                .filter(g -> g.scope().type() == scope.type() && g.scope().id().equals(scope.id()))
                .toList();
    }

    @Override
    public boolean hasInheritanceBreak(Scope scope) {
        return inheritanceBreaks.contains(key(scope));
    }

    @Override
    public void save(Grant grant) {
        grants.put(grant.id(), grant);
    }

    @Override
    public void delete(UUID grantId) {
        grants.remove(grantId);
    }

    @Override
    public Optional<Grant> find(UUID grantId) {
        return Optional.ofNullable(grants.get(grantId));
    }

    @Override
    public List<Grant> grantsMadeUnder(UUID grantId) {
        return grants.values().stream()
                .filter(g -> grantId.equals(g.grantedByGrant()))
                .toList();
    }

    public InMemoryAcl breakInheritanceAt(Scope scope) {
        inheritanceBreaks.add(key(scope));
        return this;
    }

    public InMemoryAcl with(Scope scope, Principal principal, Permission... permissions) {
        save(Grant.of(scope, principal, Set.of(permissions)));
        return this;
    }

    public InMemoryAcl withDelegable(Scope scope, Principal principal, Permission... permissions) {
        save(Grant.of(scope, principal, Set.of(permissions)).delegable());
        return this;
    }

    public InMemoryAcl withExpiring(Scope scope, Principal principal, Instant expiry,
                                    Permission... permissions) {
        save(Grant.of(scope, principal, Set.of(permissions)).expiringAt(expiry));
        return this;
    }

    public List<Grant> all() {
        return List.copyOf(grants.values());
    }

    private static String key(Scope scope) {
        return scope.type() + ":" + scope.id();
    }

    /** Per-space settings, defaulting to the secure mode. */
    public static final class Spaces implements ContentAuthorizationService.SpaceSettings {

        private final Map<String, SpacePermissionMode> modes = new HashMap<>();
        private final Map<String, Duration> grace = new HashMap<>();

        @Override
        public SpacePermissionMode modeOf(String spaceId) {
            return modes.getOrDefault(spaceId, SpacePermissionMode.INTERSECT);
        }

        @Override
        public Duration degradedGrace(String spaceId) {
            return grace.getOrDefault(spaceId, Duration.ZERO);
        }

        public Spaces mode(String spaceId, SpacePermissionMode mode) {
            modes.put(spaceId, mode);
            return this;
        }

        public Spaces grace(String spaceId, Duration duration) {
            grace.put(spaceId, duration);
            return this;
        }
    }

    /** A scriptable Jira access checker. */
    public static final class Jira implements ContentAuthorizationService.JiraAccessChecker {

        private Access access = Access.ALLOWED;
        private boolean lastKnownGood;
        private final List<String> calls = new ArrayList<>();

        @Override
        public Access canBrowse(Principal user, Scope scope) {
            calls.add(user + "@" + scope);
            return access;
        }

        @Override
        public boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since) {
            return lastKnownGood;
        }

        public Jira answering(Access access) {
            this.access = access;
            return this;
        }

        public Jira withLastKnownGood(boolean allowed) {
            this.lastKnownGood = allowed;
            return this;
        }

        public int callCount() {
            return calls.size();
        }
    }
}
