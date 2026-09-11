package dev.jvault.authz;

import dev.jvault.authz.ContentAuthorizationService.Subject;
import dev.jvault.authz.support.InMemoryAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Delegation, and the rule that nobody can give away more than they hold.
 *
 * <p>Privilege escalation here would be quiet and total: a user with {@code VIEW} who could grant
 * {@code DOWNLOAD} to a group they belong to has just given themselves {@code DOWNLOAD}.
 */
class GrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:41:12Z");

    private static final Scope SPACE = Scope.space("SEC");
    private static final Scope TICKET = SPACE.ticket("ticket-1");

    private static final Principal ADMIN = Principal.user("space-admin");
    private static final Principal LEAD = Principal.user("team-lead");
    private static final Principal ANALYST = Principal.user("analyst");
    private static final Principal RESPONDERS = Principal.group("sec-responders");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryAcl acl = new InMemoryAcl();
    private final InMemoryAcl.Spaces spaces = new InMemoryAcl.Spaces();
    private final InMemoryAcl.Jira jira = new InMemoryAcl.Jira();

    private ContentAuthorizationService authorization;
    private GrantService grants;

    @BeforeEach
    void setUp() {
        authorization = new ContentAuthorizationService(acl, jira, spaces, clock);
        grants = new GrantService(authorization, acl, clock);
    }

    @Nested
    @DisplayName("no privilege escalation")
    class NoEscalation {

        @Test
        @DisplayName("a grantor cannot grant a permission they do not hold")
        void cannotGrantWhatYouDoNotHold() {
            acl.with(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);

            assertThatThrownBy(() -> grants.grant(subject(ADMIN), SPACE, ANALYST,
                    Set.of(Permission.DOWNLOAD), false))
                    .isInstanceOf(GrantService.GrantRefusedException.class)
                    .hasMessageContaining("DOWNLOAD");
        }

        @Test
        @DisplayName("granting requires MANAGE_ACCESS, not merely holding the permission")
        void grantingRequiresManageAccess() {
            acl.with(SPACE, LEAD, Permission.VIEW, Permission.DOWNLOAD);

            assertThatThrownBy(() -> grants.grant(subject(LEAD), SPACE, ANALYST,
                    Set.of(Permission.VIEW), false))
                    .isInstanceOfSatisfying(GrantService.GrantRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("NO_MANAGE_ACCESS"));
        }

        @Test
        @DisplayName("a grantor may grant exactly what they hold")
        void canGrantWhatYouHold() {
            acl.with(SPACE, ADMIN, Permission.VIEW, Permission.DOWNLOAD, Permission.MANAGE_ACCESS);

            Grant granted = grants.grant(subject(ADMIN), SPACE, ANALYST,
                    Set.of(Permission.VIEW, Permission.DOWNLOAD), false);

            assertThat(granted.permissions())
                    .containsExactlyInAnyOrder(Permission.VIEW, Permission.DOWNLOAD);
        }

        @Test
        @DisplayName("for any grantor and any request, the result is always a subset of what they hold")
        void subsetPropertyHoldsForRandomCombinations() {
            var random = new Random(20260911L);
            var permissions = List.copyOf(EnumSet.allOf(Permission.class));

            for (int round = 0; round < 400; round++) {
                var localAcl = new InMemoryAcl();
                var localAuth = new ContentAuthorizationService(localAcl, jira, spaces, clock);
                var localGrants = new GrantService(localAuth, localAcl, clock);

                Set<Permission> held = randomSubset(random, permissions);
                Set<Permission> requested = randomSubset(random, permissions);
                if (held.isEmpty() || requested.isEmpty()) {
                    continue;
                }
                localAcl.save(Grant.of(SPACE, ADMIN, held));

                try {
                    Grant granted = localGrants.grant(subject(ADMIN), SPACE, ANALYST,
                            requested, false);
                    // The property: whatever comes out is within what the grantor had.
                    assertThat(held).as("round %d", round).containsAll(granted.permissions());
                } catch (GrantService.GrantRefusedException expected) {
                    // Refusing is always a correct outcome; granting too much never is.
                    assertThat(held.containsAll(requested)
                            && held.contains(Permission.MANAGE_ACCESS))
                            .as("round %d refused a grant it should have allowed", round)
                            .isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("delegation")
    class Delegation {

        @Test
        @DisplayName("a delegated manager can grant onward")
        void delegatedManagerCanGrant() {
            acl.withDelegable(SPACE, LEAD, Permission.VIEW, Permission.MANAGE_ACCESS);

            Grant granted = grants.grant(subject(LEAD), TICKET, ANALYST,
                    Set.of(Permission.VIEW), false);

            assertThat(granted.delegationDepth()).isEqualTo(1);
            assertThat(granted.grantedBy()).isEqualTo(LEAD);
        }

        @Test
        @DisplayName("a delegate whose grant forbids delegating onward cannot pass it on")
        void nonDelegableGrantCannotDelegate() {
            acl.withDelegable(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);
            // The lead is given management, but not the right to create further managers.
            grants.grant(subject(ADMIN), SPACE, LEAD,
                    Set.of(Permission.VIEW, Permission.MANAGE_ACCESS), false);

            // Delegating onward is off by default: it should be a decision, not an accident.
            assertThatThrownBy(() -> grants.grant(subject(LEAD), TICKET, ANALYST,
                    Set.of(Permission.VIEW), false))
                    .isInstanceOfSatisfying(GrantService.GrantRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("DELEGATION_NOT_PERMITTED"));
        }

        @Test
        @DisplayName("an administrator's own grant needs no delegation flag")
        void rootAuthorityDoesNotNeedTheFlag() {
            // A configured space administrator is exercising their own authority, not delegating
            // someone else's, so the flag has nothing to say about it.
            acl.with(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);

            Grant granted = grants.grant(subject(ADMIN), SPACE, ANALYST,
                    Set.of(Permission.VIEW), false);

            assertThat(granted.permissions()).containsExactly(Permission.VIEW);
        }

        @Test
        @DisplayName("delegation chains stop at a depth that stays auditable")
        void delegationIsDepthLimited() {
            acl.withDelegable(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);

            Grant first = grants.grant(subject(ADMIN), SPACE, LEAD,
                    Set.of(Permission.VIEW, Permission.MANAGE_ACCESS), true);
            assertThat(first.delegationDepth()).isEqualTo(1);

            Grant second = grants.grant(subject(LEAD), SPACE, ANALYST,
                    Set.of(Permission.VIEW, Permission.MANAGE_ACCESS), true);
            assertThat(second.delegationDepth()).isEqualTo(2);

            assertThatThrownBy(() -> grants.grant(subject(ANALYST), SPACE,
                    Principal.user("someone-else"), Set.of(Permission.VIEW), false))
                    .isInstanceOfSatisfying(GrantService.GrantRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("DELEGATION_TOO_DEEP"));
        }

        @Test
        @DisplayName("a grant records the authority it was made under")
        void provenanceIsRecorded() {
            acl.withDelegable(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);
            Grant authority = acl.all().get(0);

            Grant granted = grants.grant(subject(ADMIN), SPACE, ANALYST,
                    Set.of(Permission.VIEW), false);

            assertThat(granted.grantedByGrant()).isEqualTo(authority.id());
        }
    }

    @Nested
    @DisplayName("revocation cascades")
    class Revocation {

        @Test
        @DisplayName("revoking a delegation revokes everything granted under it")
        void revocationCascades() {
            acl.withDelegable(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);

            Grant delegated = grants.grant(subject(ADMIN), SPACE, LEAD,
                    Set.of(Permission.VIEW, Permission.MANAGE_ACCESS), true);
            Grant leaf = grants.grant(subject(LEAD), SPACE, ANALYST,
                    Set.of(Permission.VIEW), false);

            List<Grant> removed = grants.revoke(subject(ADMIN), delegated.id());

            // Leaving the grants a withdrawn delegation produced is the most common way access
            // outlives the reason for it, and it is invisible in any report of direct grants.
            assertThat(removed).extracting(Grant::id)
                    .containsExactlyInAnyOrder(delegated.id(), leaf.id());
            assertThat(acl.find(leaf.id())).isEmpty();
            assertThat(authorization.effectivePermissions(subject(ANALYST), SPACE)).isEmpty();
        }

        @Test
        @DisplayName("a deep chain is revoked in full")
        void deepChainsCascade() {
            acl.withDelegable(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);

            Grant first = grants.grant(subject(ADMIN), SPACE, LEAD,
                    Set.of(Permission.VIEW, Permission.MANAGE_ACCESS), true);
            grants.grant(subject(LEAD), SPACE, ANALYST, Set.of(Permission.VIEW), false);
            grants.grant(subject(LEAD), SPACE, RESPONDERS, Set.of(Permission.VIEW), false);

            assertThat(grants.revoke(subject(ADMIN), first.id())).hasSize(3);
        }

        @Test
        @DisplayName("revoking requires MANAGE_ACCESS too")
        void revokingRequiresManageAccess() {
            acl.withDelegable(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);
            Grant granted = grants.grant(subject(ADMIN), SPACE, ANALYST,
                    Set.of(Permission.VIEW), false);

            assertThatThrownBy(() -> grants.revoke(subject(ANALYST), granted.id()))
                    .isInstanceOfSatisfying(GrantService.GrantRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("NO_MANAGE_ACCESS"));
        }

        @Test
        @DisplayName("revoking something that is not there says so")
        void revokingAMissingGrant() {
            assertThatThrownBy(() -> grants.revoke(subject(ADMIN), java.util.UUID.randomUUID()))
                    .isInstanceOfSatisfying(GrantService.GrantRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("GRANT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("grant hygiene")
    class Hygiene {

        @Test
        @DisplayName("a grant with no permissions is not representable")
        void emptyGrantsAreRejected() {
            assertThatThrownBy(() -> Grant.of(SPACE, ANALYST, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("grants nothing");
        }

        @Test
        @DisplayName("a group grant reaches every member without naming them")
        void groupGrantsReachMembers() {
            acl.with(SPACE, ADMIN, Permission.VIEW, Permission.MANAGE_ACCESS);
            grants.grant(subject(ADMIN), SPACE, RESPONDERS, Set.of(Permission.VIEW), false);

            var member = new Subject(Principal.user("new-joiner"), Set.of(RESPONDERS));

            // Group membership is resolved per request, so a new joiner gains access without any
            // grant being touched — and a leaver loses it the same way.
            assertThat(authorization.effectivePermissions(member, SPACE))
                    .containsExactly(Permission.VIEW);
        }
    }

    private static Subject subject(Principal principal) {
        return Subject.of(principal);
    }

    private static Set<Permission> randomSubset(Random random, List<Permission> all) {
        var chosen = new ArrayList<Permission>();
        for (Permission permission : all) {
            if (random.nextBoolean()) {
                chosen.add(permission);
            }
        }
        return Set.copyOf(chosen);
    }
}
