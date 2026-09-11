package dev.jvault.api.security;

import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Principal;

import java.util.Objects;
import java.util.Set;

/**
 * Who is making this request.
 *
 * <p>Groups and roles are carried per request, resolved from current identity-provider state
 * rather than frozen into a session. That is what makes removing someone from a group take effect
 * without them logging out, and it is the reason this is a request-scoped value rather than
 * something cached on the session (docs/11-authorization.md 11.6).
 */
public record Caller(Principal principal, Set<Principal> groupsAndRoles, boolean jiraConnected) {

    public Caller {
        Objects.requireNonNull(principal, "principal");
        groupsAndRoles = groupsAndRoles == null ? Set.of() : Set.copyOf(groupsAndRoles);
    }

    public ContentAuthorizationService.Subject subject() {
        return new ContentAuthorizationService.Subject(principal, groupsAndRoles);
    }

    /** Identifiers only — safe to put in an audit record or a log line. */
    @Override
    public String toString() {
        return principal.toString();
    }
}
