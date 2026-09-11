package dev.jvault.authz;

/**
 * How a space combines jvault's own permissions with Jira's (docs/11-authorization.md 11.3).
 *
 * <p>Two failure modes pull in opposite directions, and only one of these modes avoids both:
 *
 * <ul>
 *   <li><strong>jvault as a bypass of Jira</strong> — content Jira would have hidden becomes
 *       reachable because the vault ACL happens to be broader.</li>
 *   <li><strong>Jira as a bypass of jvault</strong> — content was externalised <em>precisely
 *       because</em> the Jira audience is too wide; if Jira access alone sufficed, externalising
 *       it achieved nothing.</li>
 * </ul>
 */
public enum SpacePermissionMode {

    /**
     * Both must allow. <strong>The default.</strong> Neither system can be used to bypass the
     * other.
     *
     * <p>Accepted consequence: a user with no Jira connection is denied, and told to connect
     * rather than simply refused.
     */
    INTERSECT,

    /**
     * jvault's ACL alone decides.
     *
     * <p>For spaces where Jira browse permission is deliberately broad — an all-company project —
     * but vault content must reach a narrow audience. The intended mode for HR-style spaces.
     */
    VAULT_ONLY,

    /**
     * Jira alone decides.
     *
     * <p>For low-sensitivity content externalised for size or retention reasons rather than
     * confidentiality. The vault is storage here, not a security boundary.
     */
    JIRA_ONLY,

    /**
     * Either suffices.
     *
     * <p>Almost always a mistake: it makes the more permissive of two systems the effective
     * policy. Requires a typed confirmation, writes a high-severity audit event, and stays listed
     * on the security dashboard.
     */
    UNION;

    public boolean requiresJiraCheck() {
        return this == INTERSECT || this == JIRA_ONLY || this == UNION;
    }

    public boolean requiresVaultGrant() {
        return this == INTERSECT || this == VAULT_ONLY || this == UNION;
    }

    /** Whether choosing this mode should demand explicit administrator acknowledgement. */
    public boolean needsExplicitAcknowledgement() {
        return this != INTERSECT;
    }
}
