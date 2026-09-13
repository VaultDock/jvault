package dev.jvault.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jvault.authz.Grant;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.session.SessionStore;
import dev.jvault.content.CommentRecord;
import dev.jvault.content.ContentRecord;
import dev.jvault.content.TicketCommand;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.TicketRepository;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import dev.jvault.crypto.text.SensitiveTextCipher;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.Placement;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.StoredObjectRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tickets, content metadata, comments and grants against a real PostgreSQL.
 *
 * <p>The in-memory doubles these replace agree with their own assumptions by construction. What
 * they cannot show is whether the dedupe index actually stops two nodes creating the same ticket,
 * whether a filename really is unreadable in the column, or whether the SQL parses at all — and
 * the last of those has already caught two bugs in this repository.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcVaultRepositoriesTest {

    private static final String RING = "sec-restricted";
    private static final String FILENAME = "2026-Q3-layoffs-final.xlsx";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource dataSource;

    private JdbcTicketRepository tickets;
    private JdbcContentMetadataRepository content;
    private JdbcCommentRepository comments;
    private JdbcAclRepository acl;
    private Instant now;

    @BeforeAll
    static void startDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);

        new SchemaMigrator(dataSource, DialectDetector.detect(dataSource)).migrate();
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.close();
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : List.of("jira_connection", "user_session", "auth_state",
                    "acl_grant_permission", "acl_grant",
                    "acl_inheritance_break", "content_version", "content_part", "ticket_comment",
                    "ticket_field", "ticket_external_part", "ticket_record")) {
                statement.execute("DELETE FROM " + table);
            }
        }
        var cipher = new SensitiveTextCipher(LocalKeyManagementService.withKeyRings(RING));
        tickets = new JdbcTicketRepository(dataSource);
        content = new JdbcContentMetadataRepository(dataSource, cipher);
        comments = new JdbcCommentRepository(dataSource);
        acl = new JdbcAclRepository(dataSource);
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Nested
    @DisplayName("tickets")
    class Tickets {

        @Test
        @DisplayName("every field survives a write and a read")
        void roundTrips() {
            TicketRecord ticket = ticket("t-1", "dedupe-1");

            tickets.reserve(ticket);
            TicketRecord loaded = tickets.find("t-1").orElseThrow();

            assertThat(loaded.deploymentId()).isEqualTo(ticket.deploymentId());
            assertThat(loaded.projectKey()).isEqualTo("KAN");
            assertThat(loaded.dedupeKey()).isEqualTo("dedupe-1");
            assertThat(loaded.correlationId()).isEqualTo("corr-1");
            assertThat(loaded.state()).isEqualTo(TicketRecord.State.DRAFT);
            assertThat(loaded.jiraFields()).containsEntry("summary", "Disk filling up");
            assertThat(loaded.externalContentRefs()).containsExactly("c-1", "c-2");
            assertThat(loaded.createdAt()).isEqualTo(ticket.createdAt());
        }

        @Test
        @DisplayName("the origin actor survives, separately from the identity that acts in Jira")
        void originSurvives() {
            tickets.reserve(ticket("t-1", null));

            TicketCommand.Origin origin = tickets.find("t-1").orElseThrow().origin();

            // For Kafka work the Jira creator is the integration identity; the person who caused
            // it is only ever recoverable from here.
            assertThat(origin.channel()).isEqualTo(TicketCommand.Origin.Channel.UI);
            assertThat(origin.actorId()).isEqualTo("alice");
            assertThat(origin.identityRef()).isEqualTo("USER:alice");
        }

        @Test
        @DisplayName("a second ticket with the same dedupe key is refused, not duplicated")
        void dedupeKeyIsUnique() {
            tickets.reserve(ticket("t-1", "dedupe-1"));

            TicketRepository.Reservation second = tickets.reserve(ticket("t-2", "dedupe-1"));

            assertThat(second.created()).isFalse();
            assertThat(second.record().ticketRef()).isEqualTo("t-1");
        }

        @Test
        @DisplayName("racing nodes produce exactly one ticket for one dedupe key")
        void concurrentReserveYieldsOne() throws Exception {
            // The whole reason reserve does not read before it writes. Two nodes consuming the
            // same replayed message both pass a read-then-insert check.
            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            var attempts = new ArrayList<Callable<TicketRepository.Reservation>>();
            for (int i = 0; i < racers; i++) {
                String ref = "t-" + i;
                attempts.add(() -> tickets.reserve(ticket(ref, "same-key")));
            }

            List<Future<TicketRepository.Reservation>> results = pool.invokeAll(attempts);
            pool.shutdown();

            long created = 0;
            var refs = new java.util.HashSet<String>();
            for (Future<TicketRepository.Reservation> result : results) {
                TicketRepository.Reservation reservation = result.get();
                if (reservation.created()) {
                    created++;
                }
                refs.add(reservation.record().ticketRef());
            }
            assertThat(created).isEqualTo(1);
            assertThat(refs).hasSize(1);
        }

        @Test
        @DisplayName("tickets without a dedupe key do not collide with each other")
        void nullDedupeKeysDoNotCollide() {
            // A filtered unique index, not a plain one: otherwise the first keyless ticket
            // becomes a magnet that every later keyless ticket is rejected against.
            assertThat(tickets.reserve(ticket("t-1", null)).created()).isTrue();
            assertThat(tickets.reserve(ticket("t-2", null)).created()).isTrue();
            assertThat(tickets.findByDedupeKey("jira-cloud-prod", "KAN", null)).isEmpty();
        }

        @Test
        @DisplayName("saving replaces the fields rather than accumulating them")
        void saveReplacesFields() {
            tickets.reserve(ticket("t-1", null));

            tickets.save(tickets.find("t-1").orElseThrow()
                    .withFields(Map.of("summary", "Renamed"), List.of("c-9"),
                            TicketRecord.State.ACTIVE));

            TicketRecord loaded = tickets.find("t-1").orElseThrow();
            assertThat(loaded.jiraFields()).containsOnlyKeys("summary");
            assertThat(loaded.externalContentRefs()).containsExactly("c-9");
            assertThat(loaded.state()).isEqualTo(TicketRecord.State.ACTIVE);
        }
    }

    @Nested
    @DisplayName("content metadata")
    class Content {

        @Test
        @DisplayName("every field survives a write and a read")
        void roundTrips() {
            tickets.reserve(ticket("t-1", null));
            ContentRecord record = content("c-1", 1);

            content.record(record);
            ContentRecord loaded = content.findCurrent("c-1").orElseThrow();

            assertThat(loaded.versionId()).isEqualTo(record.versionId());
            assertThat(loaded.partType()).isEqualTo(PartType.DESCRIPTION);
            assertThat(loaded.fieldKey()).isEqualTo("description");
            assertThat(loaded.classification()).isEqualTo(Classification.RESTRICTED);
            assertThat(loaded.keyRing()).isEqualTo(RING);
            assertThat(loaded.wrappedDataKey()).isEqualTo(record.wrappedDataKey());
            assertThat(loaded.plaintextSha256()).isEqualTo(record.plaintextSha256());
            assertThat(loaded.ciphertextSha256()).isEqualTo(record.ciphertextSha256());
            assertThat(loaded.sizeBytes()).isEqualTo(4096L);
            assertThat(loaded.jiraSurrogate()).isEqualTo("Stored in jvault");
            assertThat(loaded.storedObject()).isEqualTo(record.storedObject());
        }

        @Test
        @DisplayName("the filename is not readable in the database")
        void displayNameIsEncryptedAtRest() throws SQLException {
            tickets.reserve(ticket("t-1", null));
            content.record(content("c-1", 1));

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT display_name_enc FROM content_version")) {
                assertThat(rows.next()).isTrue();
                String stored = new String(rows.getBytes(1), StandardCharsets.UTF_8);
                // '2026-Q3-layoffs-final.xlsx' is frequently the most sensitive thing about a
                // file. Anyone with a read on this table would otherwise have it.
                assertThat(stored).doesNotContain("layoffs");
            }

            assertThat(content.findCurrent("c-1").orElseThrow().displayName().reveal())
                    .isEqualTo(FILENAME);
        }

        @Test
        @DisplayName("a new version moves the current pointer and keeps the old one readable")
        void versionsAccumulate() {
            tickets.reserve(ticket("t-1", null));
            content.record(content("c-1", 1));
            content.record(content("c-1", 2));

            assertThat(content.findCurrent("c-1").orElseThrow().versionNo()).isEqualTo(2);
            assertThat(content.findVersion("c-1", 1)).isPresent();
            assertThat(content.versionsOf("c-1")).extracting(ContentRecord::versionNo)
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("a ticket's parts come back, current versions only")
        void partsOfTicket() {
            tickets.reserve(ticket("t-1", null));
            content.record(content("c-1", 1));
            content.record(content("c-1", 2));
            content.record(content("c-2", 1));

            assertThat(content.partsOf("t-1")).extracting(ContentRecord::contentRef)
                    .containsExactlyInAnyOrder("c-1", "c-2");
        }
    }

    @Nested
    @DisplayName("comments")
    class Comments {

        @Test
        @DisplayName("a comment round trips and updates in place")
        void roundTripsAndUpdates() {
            tickets.reserve(ticket("t-1", null));
            var comment = new CommentRecord("cm-1", "t-1", "Stored in jvault", "c-1",
                    Placement.EXTERNAL, "alice", null, now);

            comments.save(comment);
            comments.save(new CommentRecord("cm-1", "t-1", "Stored in jvault", "c-1",
                    Placement.EXTERNAL, "alice", "10501", now));

            assertThat(comments.forTicket("t-1")).hasSize(1);
            CommentRecord loaded = comments.find("cm-1").orElseThrow();
            assertThat(loaded.jiraCommentId()).isEqualTo("10501");
            assertThat(loaded.isExternallyStored()).isTrue();
        }
    }

    @Nested
    @DisplayName("sessions and Jira connections")
    class Sessions {

        private JdbcSessionStore store;

        @BeforeEach
        void openStore() {
            store = new JdbcSessionStore(dataSource,
                    new SensitiveTextCipher(LocalKeyManagementService.withKeyRings(RING)), RING);
        }

        @Test
        @DisplayName("both tokens survive a round trip")
        void tokensRoundTrip() {
            store.saveConnection(connection("access-token-value", "refresh-token-value"));

            var found = store.findConnection("acct-1", "d1").orElseThrow();

            // Sealing twice produces two data keys. Keeping only the first one is how the
            // refresh token spent a while being written and never readable — the ciphertext was
            // there and the key that made it had been thrown away.
            assertThat(found.accessToken().reveal()).isEqualTo("access-token-value");
            assertThat(found.refreshToken().reveal()).isEqualTo("refresh-token-value");
            assertThat(found.kind()).isEqualTo(SessionStore.Connection.Kind.OAUTH);
        }

        @Test
        @DisplayName("a connection with no refresh token reads back without one")
        void refreshIsOptional() {
            store.saveConnection(connection("access-only", null));

            var found = store.findConnection("acct-1", "d1").orElseThrow();

            assertThat(found.accessToken().reveal()).isEqualTo("access-only");
            assertThat(found.refreshToken()).isNull();
            assertThat(found.isRenewable()).isFalse();
        }

        @Test
        @DisplayName("re-authorizing replaces both tokens, not just the one")
        void reauthorizationReplacesBoth() {
            store.saveConnection(connection("first-access", "first-refresh"));
            store.saveConnection(connection("second-access", "second-refresh"));

            var found = store.findConnection("acct-1", "d1").orElseThrow();

            // A refresh rotates the refresh token too, so an update that renewed one and left
            // the other would leave a pair that no longer belongs together.
            assertThat(found.accessToken().reveal()).isEqualTo("second-access");
            assertThat(found.refreshToken().reveal()).isEqualTo("second-refresh");
        }

        @Test
        @DisplayName("a token is not readable in the database")
        void tokensAreEncryptedAtRest() throws SQLException {
            store.saveConnection(connection("SUPER-SECRET-ACCESS", "SUPER-SECRET-REFRESH"));

            try (Connection sql = dataSource.getConnection();
                 Statement statement = sql.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT encode(access_token_enc,'escape')||' '"
                                 + "||encode(refresh_token_enc,'escape') FROM jira_connection")) {
                assertThat(rows.next()).isTrue();
                // A readable access token is that person's Jira access; a readable refresh token
                // is that access renewed indefinitely.
                assertThat(rows.getString(1)).doesNotContain("SUPER-SECRET");
            }
        }

        @Test
        @DisplayName("a state value can be redeemed once and never again")
        void statesAreSingleUse() {
            store.rememberState("state-1", "/tickets", now.plusSeconds(600));

            assertThat(store.redeemState("state-1")).contains("/tickets");
            // Replaying a callback must not work twice: without this an attacker who observes
            // one can have it redeemed into their own browser.
            assertThat(store.redeemState("state-1")).isEmpty();
        }

        @Test
        @DisplayName("an expired state is not redeemable")
        void expiredStatesAreRefused() {
            store.rememberState("state-2", "/", now.minusSeconds(1));

            assertThat(store.redeemState("state-2")).isEmpty();
        }

        @Test
        @DisplayName("a session ends when it is ended, and when it expires")
        void sessionsEnd() {
            store.createSession(new SessionStore.Session("sess-1", "acct-1", "Ada", null,
                    "en_GB", now, now.plusSeconds(600)));
            assertThat(store.findSession("sess-1")).isPresent();

            store.endSession("sess-1");
            assertThat(store.findSession("sess-1")).isEmpty();

            store.createSession(new SessionStore.Session("sess-2", "acct-1", "Ada", null,
                    "en_GB", now.minusSeconds(60), now.minusSeconds(1)));
            assertThat(store.findSession("sess-2")).isEmpty();
        }

        private SessionStore.Connection connection(String access, String refresh) {
            return new SessionStore.Connection("acct-1", "d1", "cloud-1",
                    "https://acme.atlassian.net", null, SessionStore.Connection.Kind.OAUTH,
                    SensitiveValue.of(access, "jira.accessToken"),
                    refresh == null ? null : SensitiveValue.of(refresh, "jira.refreshToken"),
                    now.plusSeconds(3600), "read:me read:jira-work", "OAUTH_3LO_NO_PKCE");
        }
    }

    @Nested
    @DisplayName("grants")
    class Grants {

        private final Scope space = Scope.space("KAN");

        @Test
        @DisplayName("a grant round trips with its scope chain and permissions")
        void roundTrips() {
            Scope part = space.ticket("t-1").part("c-1");
            Grant grant = new Grant(UUID.randomUUID(), part, Principal.user("alice"),
                    Set.of(Permission.VIEW, Permission.DOWNLOAD), true, 1, null,
                    Principal.user("admin"), now.plusSeconds(3600), now);

            acl.save(grant);
            List<Grant> found = acl.grantsAt(part);

            assertThat(found).hasSize(1);
            Grant loaded = found.get(0);
            assertThat(loaded.permissions()).containsExactlyInAnyOrder(
                    Permission.VIEW, Permission.DOWNLOAD);
            assertThat(loaded.canDelegate()).isTrue();
            assertThat(loaded.delegationDepth()).isEqualTo(1);
            assertThat(loaded.grantedBy()).isEqualTo(Principal.user("admin"));
            assertThat(loaded.expiresAt()).isEqualTo(grant.expiresAt());
            // The chain matters: an authorization walk goes part, ticket, space.
            assertThat(loaded.scope()).isEqualTo(part);
            assertThat(loaded.scope().spaceScope()).isEqualTo(space);
        }

        @Test
        @DisplayName("re-saving a grant replaces its permissions rather than adding to them")
        void savingReplacesPermissions() {
            UUID id = UUID.randomUUID();
            acl.save(new Grant(id, space, Principal.user("alice"),
                    Set.of(Permission.VIEW, Permission.DOWNLOAD), false, 0, null, null, null, now));

            acl.save(new Grant(id, space, Principal.user("alice"), Set.of(Permission.VIEW),
                    false, 0, null, null, null, now));

            // Otherwise a revoked permission survives the re-grant that was meant to remove it.
            assertThat(acl.grantsAt(space).get(0).permissions())
                    .containsExactly(Permission.VIEW);
        }

        @Test
        @DisplayName("grants made under a grant can be found, so revocation can cascade")
        void findsDelegatedGrants() {
            UUID parent = UUID.randomUUID();
            acl.save(new Grant(parent, space, Principal.user("alice"), Set.of(Permission.VIEW),
                    true, 0, null, null, null, now));
            acl.save(new Grant(UUID.randomUUID(), space, Principal.user("bob"),
                    Set.of(Permission.VIEW), false, 1, parent, Principal.user("alice"), null, now));

            assertThat(acl.grantsMadeUnder(parent)).hasSize(1);
            assertThat(acl.grantsMadeUnder(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("deleting a grant leaves no permission rows behind")
        void deleteRemovesPermissions() {
            UUID id = UUID.randomUUID();
            acl.save(new Grant(id, space, Principal.user("alice"), Set.of(Permission.VIEW),
                    false, 0, null, null, null, now));

            acl.delete(id);

            assertThat(acl.grantsAt(space)).isEmpty();
            assertThat(acl.permissionsOf(id)).isEmpty();
            assertThat(acl.find(id)).isEmpty();
        }

        @Test
        @DisplayName("an inheritance break is recorded and is idempotent")
        void inheritanceBreaks() {
            Scope ticket = space.ticket("t-1");
            assertThat(acl.hasInheritanceBreak(ticket)).isFalse();

            acl.breakInheritance(ticket);
            acl.breakInheritance(ticket);

            assertThat(acl.hasInheritanceBreak(ticket)).isTrue();
            assertThat(acl.hasInheritanceBreak(space)).isFalse();
        }
    }

    // --- fixtures ----------------------------------------------------------------

    private TicketRecord ticket(String ref, String dedupeKey) {
        return new TicketRecord(ref, "jira-cloud-prod", "KAN", "10004", dedupeKey, "corr-1",
                TicketCommand.Origin.ui("alice", "Alice"),
                Map.of("summary", "Disk filling up"), List.of("c-1", "c-2"),
                TicketRecord.State.DRAFT, null, null, now);
    }

    private ContentRecord content(String contentRef, int versionNo) {
        String versionId = "v" + contentRef.replace("-", "") + versionNo;
        return new ContentRecord(contentRef, versionId, versionNo, "t-1", PartType.DESCRIPTION,
                "description", Classification.RESTRICTED, RING, "kek-1",
                "wrapped".getBytes(StandardCharsets.UTF_8),
                "plainhash".getBytes(StandardCharsets.UTF_8),
                "cipherhash".getBytes(StandardCharsets.UTF_8),
                4096L, "text/plain", SensitiveValue.of(FILENAME, "filename"),
                "Stored in jvault",
                new StoredObjectRef("fs-local", new ObjectKey("acme", contentRef.replace("-", ""),
                        versionId)),
                now);
    }
}
