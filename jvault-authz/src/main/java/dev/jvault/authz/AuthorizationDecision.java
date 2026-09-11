package dev.jvault.authz;

import java.util.Objects;
import java.util.Set;

/**
 * The outcome of an authorization check, with the reason attached.
 *
 * <p>The reason is not decoration. "Why can this person see this?" is the first question every
 * access review asks, and answering it from an audit log alone is painful — so the decision
 * carries it, the API can surface it to an administrator, and the audit record stores it.
 *
 * @param reason a stable code, safe for logs, metrics and audit records
 */
public record AuthorizationDecision(Outcome outcome,
                                    String reason,
                                    Set<Permission> effectivePermissions) {

    public enum Outcome {
        ALLOW,

        /** The principal is known and lacks the permission. */
        DENY,

        /**
         * A dependency needed to decide was unavailable.
         *
         * <p>Distinct from {@link #DENY} on purpose: a user locked out by a Jira outage should be
         * told the system cannot check right now, not that they have lost access. The two produce
         * different HTTP statuses and very different support tickets.
         */
        UNAVAILABLE
    }

    public AuthorizationDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        effectivePermissions = effectivePermissions == null ? Set.of() : Set.copyOf(effectivePermissions);
    }

    public boolean isAllowed() {
        return outcome == Outcome.ALLOW;
    }

    public static AuthorizationDecision allow(String reason, Set<Permission> effective) {
        return new AuthorizationDecision(Outcome.ALLOW, reason, effective);
    }

    public static AuthorizationDecision deny(String reason, Set<Permission> effective) {
        return new AuthorizationDecision(Outcome.DENY, reason, effective);
    }

    public static AuthorizationDecision unavailable(String reason) {
        return new AuthorizationDecision(Outcome.UNAVAILABLE, reason, Set.of());
    }

    // Reason codes, named so that dashboards and alerts can rely on them.
    public static final String NO_VAULT_GRANT = "NO_VAULT_GRANT";
    public static final String JIRA_NO_BROWSE = "JIRA_NO_BROWSE";
    public static final String JIRA_NOT_CONNECTED = "JIRA_NOT_CONNECTED";
    public static final String JIRA_UNAVAILABLE = "JIRA_UNAVAILABLE";
    public static final String GRANTED = "GRANTED";
    public static final String GRANTED_BY_JIRA = "GRANTED_BY_JIRA";
    public static final String DEGRADED_ALLOW = "DEGRADED_ALLOW";
}
