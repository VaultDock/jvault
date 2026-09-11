package dev.jvault.authz;

import dev.jvault.authz.ContentAuthorizationService.JiraAccessChecker.Access;
import dev.jvault.authz.ContentAuthorizationService.Subject;
import dev.jvault.authz.support.InMemoryAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide who may read externally stored content.
 *
 * <p>Every one of these is a rule the design argued for in prose; here they are as behaviour.
 */
class ContentAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:41:12Z");

    private static final Scope SPACE = Scope.space("SEC");
    private static final Scope TICKET = SPACE.ticket("ticket-1");
    private static final Scope PART = TICKET.part("content-1");

    private static final Principal ALICE = Principal.user("alice");
    private static final Principal RESPONDERS = Principal.group("sec-responders");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryAcl acl = new InMemoryAcl();
    private final InMemoryAcl.Spaces spaces = new InMemoryAcl.Spaces();
    private final InMemoryAcl.Jira jira = new InMemoryAcl.Jira();

    private ContentAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        authorization = new ContentAuthorizationService(acl, jira, spaces, clock);
    }

    @Nested
    @DisplayName("effective permissions")
    class Effective {

        @Test
        @DisplayName("a grant at the space reaches a part beneath it")
        void grantsInherit() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW, Permission.DOWNLOAD);

            assertThat(authorization.effectivePermissions(responder(), PART))
                    .containsExactlyInAnyOrder(Permission.VIEW, Permission.DOWNLOAD);
        }

        @Test
        @DisplayName("grants from several levels union together")
        void grantsUnion() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            acl.with(TICKET, ALICE, Permission.DOWNLOAD);
            acl.with(PART, RESPONDERS, Permission.EDIT);

            assertThat(authorization.effectivePermissions(responder(), PART))
                    .containsExactlyInAnyOrder(Permission.VIEW, Permission.DOWNLOAD, Permission.EDIT);
        }

        @Test
        @DisplayName("an inheritance break stops grants from above")
        void inheritanceBreak() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW, Permission.DOWNLOAD);
            acl.with(PART, RESPONDERS, Permission.VIEW);
            acl.breakInheritanceAt(PART);

            // The tool for restricting a subtree is a break, not a deny — a deny would need an
            // evaluation order, and evaluation order makes "why can they see this?" unanswerable.
            assertThat(authorization.effectivePermissions(responder(), PART))
                    .containsExactly(Permission.VIEW);
        }

        @Test
        @DisplayName("an expired grant counts for nothing")
        void expiredGrantsAreIgnored() {
            acl.withExpiring(SPACE, RESPONDERS, NOW.minusSeconds(1), Permission.VIEW);

            assertThat(authorization.effectivePermissions(responder(), PART)).isEmpty();
        }

        @Test
        @DisplayName("a grant expiring in the future still counts")
        void unexpiredGrantsApply() {
            acl.withExpiring(SPACE, RESPONDERS, NOW.plusSeconds(60), Permission.VIEW);

            assertThat(authorization.effectivePermissions(responder(), PART))
                    .containsExactly(Permission.VIEW);
        }

        @Test
        @DisplayName("a grant to a group the caller is not in does not apply")
        void unrelatedGroupsDoNotApply() {
            acl.with(SPACE, Principal.group("finance"), Permission.VIEW);

            assertThat(authorization.effectivePermissions(responder(), PART)).isEmpty();
        }
    }

    @Nested
    @DisplayName("download is not view")
    class DownloadIsSeparate {

        @Test
        @DisplayName("VIEW alone does not permit taking a copy")
        void viewDoesNotImplyDownload() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);

            // "Read the narrative in the browser, but do not put the evidence on a laptop" is a
            // requirement that collapsing these two would make inexpressible.
            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isTrue();
            assertThat(authorization.authorize(responder(), PART, Permission.DOWNLOAD).isAllowed())
                    .isFalse();
        }

        @Test
        @DisplayName("EDIT does not imply DELETE")
        void editDoesNotImplyDelete() {
            acl.with(SPACE, RESPONDERS, Permission.EDIT);

            assertThat(authorization.authorize(responder(), PART, Permission.DELETE).isAllowed())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("INTERSECT — the secure default")
    class Intersect {

        @Test
        @DisplayName("both jvault and Jira must allow")
        void bothMustAllow() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.ALLOWED);

            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isTrue();
        }

        @Test
        @DisplayName("a Jira user without a vault grant is denied")
        void jiraAloneIsNotEnough() {
            jira.answering(Access.ALLOWED);

            var decision = authorization.authorize(responder(), PART, Permission.VIEW);

            // Otherwise Jira becomes a bypass of the vault, and externalising the content
            // achieved nothing.
            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(AuthorizationDecision.NO_VAULT_GRANT);
        }

        @Test
        @DisplayName("a vault grant without Jira access is denied")
        void vaultAloneIsNotEnough() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.DENIED);

            var decision = authorization.authorize(responder(), PART, Permission.VIEW);

            // Otherwise jvault becomes a bypass of the project's own security scheme.
            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(AuthorizationDecision.JIRA_NO_BROWSE);
        }

        @Test
        @DisplayName("a user who has not connected Jira is told so, not merely refused")
        void notConnectedIsItsOwnAnswer() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.NOT_CONNECTED);

            var decision = authorization.authorize(responder(), PART, Permission.VIEW);

            // The caller turns this into a "Connect Jira" prompt rather than an access-denied
            // page, which is the difference between a fixable state and a dead end.
            assertThat(decision.reason()).isEqualTo(AuthorizationDecision.JIRA_NOT_CONNECTED);
        }

        @Test
        @DisplayName("Jira is not asked when the vault answer already decides it")
        void doesNotSpendAJiraCallForNothing() {
            jira.answering(Access.ALLOWED);

            authorization.authorize(responder(), PART, Permission.DOWNLOAD);

            // A Jira call costs a slice of a rate-limit budget that user-facing work needs.
            assertThat(jira.callCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the other modes")
    class OtherModes {

        @Test
        @DisplayName("VAULT_ONLY ignores Jira entirely")
        void vaultOnly() {
            spaces.mode("SEC", SpacePermissionMode.VAULT_ONLY);
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.DENIED);

            // For a space where Jira browse is deliberately broad but vault content must reach a
            // narrow audience.
            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isTrue();
            assertThat(jira.callCount()).isZero();
        }

        @Test
        @DisplayName("JIRA_ONLY ignores the vault ACL")
        void jiraOnly() {
            spaces.mode("SEC", SpacePermissionMode.JIRA_ONLY);
            jira.answering(Access.ALLOWED);

            var decision = authorization.authorize(responder(), PART, Permission.VIEW);

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.reason()).isEqualTo(AuthorizationDecision.GRANTED_BY_JIRA);
        }

        @Test
        @DisplayName("UNION lets either side allow, which is why it needs acknowledging")
        void union() {
            spaces.mode("SEC", SpacePermissionMode.UNION);
            jira.answering(Access.ALLOWED);

            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isTrue();
            assertThat(SpacePermissionMode.UNION.needsExplicitAcknowledgement()).isTrue();
            assertThat(SpacePermissionMode.INTERSECT.needsExplicitAcknowledgement()).isFalse();
        }
    }

    @Nested
    @DisplayName("when Jira is unavailable")
    class Degraded {

        @Test
        @DisplayName("the default is to fail closed, and to say the system cannot check")
        void failsClosedByDefault() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.UNAVAILABLE);

            var decision = authorization.authorize(responder(), PART, Permission.VIEW);

            // UNAVAILABLE, not DENY: a user locked out by an outage should be told the check
            // cannot be made, not that they have lost access.
            assertThat(decision.outcome())
                    .isEqualTo(AuthorizationDecision.Outcome.UNAVAILABLE);
            assertThat(decision.isAllowed()).isFalse();
        }

        @Test
        @DisplayName("a configured grace window reuses a previously granted decision")
        void graceWindowReusesLastKnownGood() {
            spaces.grace("SEC", Duration.ofMinutes(5));
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.UNAVAILABLE).withLastKnownGood(true);

            var decision = authorization.authorize(responder(), PART, Permission.VIEW);

            // Incident response is exactly when this content is needed, and a Jira outage should
            // not lock a security team out of its own evidence.
            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.reason()).isEqualTo(AuthorizationDecision.DEGRADED_ALLOW);
        }

        @Test
        @DisplayName("the grace window never manufactures an allow that never happened")
        void graceWindowNeedsAPriorAllow() {
            spaces.grace("SEC", Duration.ofMinutes(5));
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.UNAVAILABLE).withLastKnownGood(false);

            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).outcome())
                    .isEqualTo(AuthorizationDecision.Outcome.UNAVAILABLE);
        }

        @Test
        @DisplayName("the grace window covers reading, never writing")
        void graceWindowDoesNotCoverWrites() {
            spaces.grace("SEC", Duration.ofMinutes(5));
            acl.with(SPACE, RESPONDERS, Permission.VIEW, Permission.EDIT, Permission.DELETE);
            jira.answering(Access.UNAVAILABLE).withLastKnownGood(true);

            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isTrue();
            // Acting on a stale permission to change state is awkward to unwind afterwards.
            assertThat(authorization.authorize(responder(), PART, Permission.EDIT).outcome())
                    .isEqualTo(AuthorizationDecision.Outcome.UNAVAILABLE);
            assertThat(authorization.authorize(responder(), PART, Permission.DELETE).outcome())
                    .isEqualTo(AuthorizationDecision.Outcome.UNAVAILABLE);
        }

        @Test
        @DisplayName("a user with no vault grant gets nothing from the grace window")
        void graceWindowIsNotABackDoor() {
            spaces.grace("SEC", Duration.ofMinutes(5));
            jira.answering(Access.UNAVAILABLE).withLastKnownGood(true);

            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("existence hiding")
    class ExistenceHiding {

        @Test
        @DisplayName("someone with no access to the space cannot confirm the content exists")
        void spaceOutsidersSeeNothing() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);

            // The caller turns this into 404 rather than 403, so probing content references
            // cannot be used to enumerate what exists.
            assertThat(authorization.canRevealExistence(
                    Subject.of(Principal.user("mallory")), PART)).isFalse();
        }

        @Test
        @DisplayName("someone inside the space gets a clear refusal instead")
        void spaceMembersGetAClearAnswer() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);

            // Existence is already known here, so a confusing 404 helps nobody.
            assertThat(authorization.canRevealExistence(responder(), PART)).isTrue();
        }
    }

    @Nested
    @DisplayName("possession of a link grants nothing")
    class LinksAreNotCapabilities {

        @Test
        @DisplayName("knowing the exact content reference changes nothing")
        void knowingTheReferenceIsNotAccess() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW, Permission.DOWNLOAD);
            jira.answering(Access.ALLOWED);

            var stranger = Subject.of(Principal.user("mallory"));

            // A link shared into the wrong chat, pasted into a ticket, or found in a referrer
            // header names content; it carries no authority.
            assertThat(authorization.authorize(stranger, PART, Permission.DOWNLOAD).isAllowed())
                    .isFalse();
            assertThat(authorization.authorize(stranger, PART, Permission.VIEW).isAllowed())
                    .isFalse();
        }

        @Test
        @DisplayName("every request is decided afresh, with no sticky allow")
        void decisionsAreNotSticky() {
            acl.with(SPACE, RESPONDERS, Permission.VIEW);
            jira.answering(Access.ALLOWED);
            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isTrue();

            // Jira access is revoked between one request and the next.
            jira.answering(Access.DENIED);

            assertThat(authorization.authorize(responder(), PART, Permission.VIEW).isAllowed())
                    .isFalse();
        }
    }

    private static Subject responder() {
        return new Subject(ALICE, Set.of(RESPONDERS));
    }
}
