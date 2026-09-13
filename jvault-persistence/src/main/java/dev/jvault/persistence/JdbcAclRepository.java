package dev.jvault.persistence;

import dev.jvault.authz.Grant;
import dev.jvault.authz.GrantService;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Grants on Spring JDBC.
 *
 * <p>Never the whole answer to "may this person read this". A read is allowed only where a grant
 * here and a live Jira permission check agree, so what this table can do on its own is withhold
 * access, not confer it (docs/10-authorization.md, the INTERSECT rule).
 *
 * <p>A scope is stored flattened — its own type and id, its parent's, and the space at the root —
 * rather than as a self-referencing row per level. The tree is three deep by construction and the
 * query every decision makes is "the grants at exactly this scope", which a recursive structure
 * would turn into a recursive query on three engines with three different syntaxes for it.
 */
public final class JdbcAclRepository implements GrantService.MutableAclRepository {

    private static final String COLUMNS = """
            grant_id, scope_type, scope_id, scope_parent_type, scope_parent_id, space_id,
            principal_kind, principal_id, can_delegate, delegation_depth, granted_by_grant,
            granted_by_kind, granted_by_id, expires_at, created_at""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAclRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public List<Grant> grantsAt(Scope scope) {
        return jdbc.query("SELECT " + COLUMNS + " FROM acl_grant"
                        + " WHERE scope_type = ? AND scope_id = ?",
                this::mapGrant, scope.type().name(), scope.id());
    }

    @Override
    public boolean hasInheritanceBreak(Scope scope) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM acl_inheritance_break WHERE scope_type = ? AND scope_id = ?",
                Integer.class, scope.type().name(), scope.id());
        return count != null && count > 0;
    }

    /** Stops a scope inheriting from its parent. Idempotent: breaking a break is still a break. */
    public void breakInheritance(Scope scope) {
        if (!hasInheritanceBreak(scope)) {
            jdbc.update("INSERT INTO acl_inheritance_break (scope_type, scope_id) VALUES (?, ?)",
                    scope.type().name(), scope.id());
        }
    }

    @Override
    public void save(Grant grant) {
        transactions.executeWithoutResult(status -> {
            // Replaced rather than merged: a grant's permission set is the grant. Adding to it
            // without removing what is gone is how a revoked permission survives a re-grant.
            delete(grant.id());
            insert(grant);
        });
    }

    private void insert(Grant grant) {
        Scope scope = grant.scope();
        Scope parent = scope.parent();

        jdbc.update("INSERT INTO acl_grant (" + COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                grant.id().toString(),
                scope.type().name(),
                scope.id(),
                parent == null ? null : parent.type().name(),
                parent == null ? null : parent.id(),
                scope.spaceScope().id(),
                grant.principal().kind().name(),
                grant.principal().externalId(),
                grant.canDelegate() ? 1 : 0,
                grant.delegationDepth(),
                grant.grantedByGrant() == null ? null : grant.grantedByGrant().toString(),
                grant.grantedBy() == null ? null : grant.grantedBy().kind().name(),
                grant.grantedBy() == null ? null : grant.grantedBy().externalId(),
                grant.expiresAt() == null ? null : Timestamp.from(grant.expiresAt()),
                Timestamp.from(grant.createdAt()));

        for (Permission permission : grant.permissions()) {
            jdbc.update("INSERT INTO acl_grant_permission (grant_id, permission) VALUES (?, ?)",
                    grant.id().toString(), permission.name());
        }
    }

    @Override
    public void delete(UUID grantId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM acl_grant_permission WHERE grant_id = ?", grantId.toString());
            jdbc.update("DELETE FROM acl_grant WHERE grant_id = ?", grantId.toString());
        });
    }

    @Override
    public Optional<Grant> find(UUID grantId) {
        List<Grant> found = jdbc.query("SELECT " + COLUMNS + " FROM acl_grant WHERE grant_id = ?",
                this::mapGrant, grantId.toString());
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<Grant> grantsMadeUnder(UUID grantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM acl_grant WHERE granted_by_grant = ?",
                this::mapGrant, grantId.toString());
    }

    private Grant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        String grantId = rs.getString("grant_id").trim();

        var permissions = new LinkedHashSet<Permission>();
        jdbc.query("SELECT permission FROM acl_grant_permission WHERE grant_id = ?",
                (RowCallbackHandler) row -> permissions.add(Permission.valueOf(row.getString(1))),
                grantId);

        if (permissions.isEmpty()) {
            // Grant's own constructor refuses this, and rightly: a grant with no permissions
            // grants nothing while still looking like an allowance to anyone reading the table.
            throw new PersistenceException(
                    "grant " + grantId + " has no permissions; its permission rows are missing",
                    null);
        }

        String grantedByGrant = rs.getString("granted_by_grant");
        String grantedByKind = rs.getString("granted_by_kind");
        Timestamp expiresAt = rs.getTimestamp("expires_at");

        return new Grant(
                UUID.fromString(grantId),
                scope(rs),
                new Principal(Principal.Kind.valueOf(rs.getString("principal_kind")),
                        rs.getString("principal_id")),
                permissions,
                rs.getInt("can_delegate") != 0,
                rs.getInt("delegation_depth"),
                grantedByGrant == null ? null : UUID.fromString(grantedByGrant.trim()),
                grantedByKind == null ? null
                        : new Principal(Principal.Kind.valueOf(grantedByKind),
                                rs.getString("granted_by_id")),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Rebuilds the chain from the flattened columns: space, then ticket, then part. */
    private static Scope scope(ResultSet rs) throws SQLException {
        Scope.Type type = Scope.Type.valueOf(rs.getString("scope_type"));
        String id = rs.getString("scope_id");
        Scope space = Scope.space(rs.getString("space_id"));

        return switch (type) {
            case SPACE -> space;
            case TICKET -> space.ticket(id);
            case PART -> space.ticket(rs.getString("scope_parent_id")).part(id);
        };
    }

    /** Every grant in a space, for an administrator's view of who can reach what. */
    public List<Grant> grantsInSpace(String spaceId) {
        var grants = new ArrayList<>(jdbc.query(
                "SELECT " + COLUMNS + " FROM acl_grant WHERE space_id = ? ORDER BY created_at",
                this::mapGrant, spaceId));
        return List.copyOf(grants);
    }

    /** The permissions a grant carries, for callers that only need the set. */
    public Set<Permission> permissionsOf(UUID grantId) {
        var permissions = new LinkedHashSet<Permission>();
        jdbc.query("SELECT permission FROM acl_grant_permission WHERE grant_id = ?",
                (RowCallbackHandler) row -> permissions.add(Permission.valueOf(row.getString(1))),
                grantId.toString());
        return Set.copyOf(permissions);
    }
}
