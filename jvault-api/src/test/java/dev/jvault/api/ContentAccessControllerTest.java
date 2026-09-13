package dev.jvault.api;

import dev.jvault.api.content.ContentAccessController;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Grant;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.SpacePermissionMode;
import dev.jvault.content.ContentRecord;
import dev.jvault.content.ContentService;
import dev.jvault.content.PartDescriptor;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.support.InMemoryContentMetadataRepository;
import dev.jvault.content.support.InMemoryTicketRepository;
import dev.jvault.crypto.envelope.ContentCipher;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PartType;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The content endpoint, which is where several of the design's promises become observable
 * behaviour rather than prose.
 */
class ContentAccessControllerTest {

    private static final String SECRET = "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENGbPxRfiCY";
    private static final String FILENAME = "2026-Q3-incident-evidence.pcap";
    private static final String DEPLOYMENT = "jira-cloud-prod";
    private static final String SPACE_ID = DEPLOYMENT + "/SEC";

    private static final Principal ALICE = Principal.user("alice");
    private static final Principal RESPONDERS = Principal.group("sec-responders");
    private static final Principal MALLORY = Principal.user("mallory");

    private final TestBeans.TestAcl acl = new TestBeans.TestAcl();
    private final TestBeans.TestJira jira = new TestBeans.TestJira();
    private final TestBeans.MutableCallerResolver callers = new TestBeans.MutableCallerResolver();
    private final TestBeans.RecordingAuditor auditor = new TestBeans.RecordingAuditor();
    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();

    private ContentService contentService;
    private MockMvc mvc;
    private String contentRef;

    @BeforeEach
    void setUp() throws Exception {
        var beans = new TestBeans();
        var clock = beans.clock();
        var metadata = beans.contentMetadata();
        contentService = beans.contentService(metadata, clock);
        var authorization = beans.authorization(acl, jira, clock);

        var controller = new ContentAccessController(contentService, tickets, authorization,
                callers, auditor, "/api/v1/jira/connections/start");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        acl.reset();
        auditor.reset();
        jira.allow(true);
        callers.as(new Caller(ALICE, Set.of(RESPONDERS), true));

        tickets.save(new TicketRecord("ticket-1", DEPLOYMENT, "SEC", "10004", null, "corr-1",
                null, java.util.Map.of(), List.of(), TicketRecord.State.ACTIVE,
                "10001", "SEC-4471", Instant.EPOCH));

        ContentRecord stored = contentService.store(
                new PartDescriptor("ticket-1", PartType.ATTACHMENT, null,
                        SensitiveValue.of(FILENAME, "attachment.fileName"),
                        "application/vnd.tcpdump.pcap", Classification.RESTRICTED,
                        "sec-restricted", null),
                new java.io.ByteArrayInputStream(SECRET.getBytes(StandardCharsets.UTF_8)));
        contentRef = stored.contentRef();
    }

    @Nested
    @DisplayName("the link is not a capability")
    class LinkIsNotACapability {

        @Test
        @DisplayName("an anonymous request gets 401, never content")
        void anonymousIsRefused() throws Exception {
            callers.anonymous();

            mvc.perform(get("/c/" + contentRef))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("AWS_SECRET"))));
        }

        @Test
        @DisplayName("holding the exact reference is not access")
        void strangerWithTheReferenceIsRefused() throws Exception {
            callers.as(new Caller(MALLORY, Set.of(), true));

            // A link pasted into the wrong chat names content; it carries no authority.
            mvc.perform(get("/c/" + contentRef)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an authorized caller gets the bytes")
        void authorizedCallerGetsContent() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);

            mvc.perform(get("/c/" + contentRef))
                    .andExpect(status().isOk())
                    .andExpect(content().string(SECRET));
        }
    }

    @Nested
    @DisplayName("existence hiding")
    class ExistenceHiding {

        @Test
        @DisplayName("someone outside the space cannot tell a real reference from a made-up one")
        void outsidersGet404ForBoth() throws Exception {
            callers.as(new Caller(MALLORY, Set.of(), true));

            int real = mvc.perform(get("/api/v1/content/" + contentRef))
                    .andReturn().getResponse().getStatus();
            int invented = mvc.perform(get("/api/v1/content/00000000000000000000000000000000"))
                    .andReturn().getResponse().getStatus();

            // Identical answers, so probing cannot enumerate what exists.
            assertThat(real).isEqualTo(404).isEqualTo(invented);
        }

        @Test
        @DisplayName("someone inside the space gets a clear refusal instead of a confusing 404")
        void insidersGet403() throws Exception {
            // VIEW on the space, but no DOWNLOAD: existence is already known to them.
            acl.grant(Permission.VIEW);

            mvc.perform(get("/c/" + contentRef))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Nested
    @DisplayName("every request is decided afresh")
    class DecidedAfresh {

        @Test
        @DisplayName("VIEW permits metadata but not the bytes")
        void viewDoesNotImplyDownload() throws Exception {
            acl.grant(Permission.VIEW);

            mvc.perform(get("/api/v1/content/" + contentRef)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/content/" + contentRef + "/download"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a revoked grant stops working on the very next request")
        void revocationTakesEffectImmediately() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);
            mvc.perform(get("/c/" + contentRef)).andExpect(status().isOk());

            acl.reset();

            // No sticky allow, no session-level "already authorized for this object".
            mvc.perform(get("/c/" + contentRef)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("losing Jira access stops working on the very next request")
        void jiraRevocationTakesEffectImmediately() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);
            mvc.perform(get("/c/" + contentRef)).andExpect(status().isOk());

            jira.allow(false);

            mvc.perform(get("/c/" + contentRef)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("responses reveal nothing they should not")
    class ResponsesRevealNothing {

        @Test
        @DisplayName("metadata carries no filename, no storage location and no key material")
        void metadataIsMinimal() throws Exception {
            acl.grant(Permission.VIEW);

            String body = mvc.perform(get("/api/v1/content/" + contentRef))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain(FILENAME)
                    .doesNotContain("fs-local")
                    .doesNotContain("wrappedDataKey")
                    .doesNotContain("storedObject")
                    .contains("RESTRICTED");
        }

        @Test
        @DisplayName("a download is never cached to disk by the browser")
        void downloadsAreNoStore() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);

            mvc.perform(get("/c/" + contentRef))
                    .andExpect(header().string("Cache-Control",
                            org.hamcrest.Matchers.containsString("no-store")));
        }

        @Test
        @DisplayName("the filename reaches the browser only once the caller is authorized for it")
        void filenameOnlyInAnAuthorizedDownload() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);

            mvc.perform(get("/c/" + contentRef))
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.containsString(FILENAME)));
        }

        @Test
        @DisplayName("an error body never contains the content it refused to serve")
        void errorsCarryNoContent() throws Exception {
            acl.grant(Permission.VIEW);

            String body = mvc.perform(get("/c/" + contentRef))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("AWS_SECRET").doesNotContain(FILENAME);
            assertThat(body).contains("NO_VAULT_GRANT");
        }
    }

    @Nested
    @DisplayName("a missing prerequisite is not a refusal")
    class Prerequisites {

        @Test
        @DisplayName("a caller who has not connected Jira is told how to, not simply denied")
        void notConnectedOffersAWayForward() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);
            jira.notConnected();

            mvc.perform(get("/c/" + contentRef))
                    .andExpect(status().isConflict())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("connectUrl")));
        }

        @Test
        @DisplayName("a Jira outage says the check cannot be made, not that access was lost")
        void outageIsNotADenial() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);
            jira.unavailable();

            // 503, not 403: very different support tickets.
            mvc.perform(get("/c/" + contentRef)).andExpect(status().isServiceUnavailable());
        }
    }

    @Nested
    @DisplayName("auditing")
    class Auditing {

        @Test
        @DisplayName("a denial is recorded as prominently as a success")
        void denialsAreAudited() throws Exception {
            acl.grant(Permission.VIEW);
            mvc.perform(get("/c/" + contentRef));

            // A rising denial rate against one reference is what a shared link looks like from
            // the inside, so these are the events most worth keeping.
            assertThat(auditor.entries()).anySatisfy(entry ->
                    assertThat(entry).contains("NO_VAULT_GRANT").contains(contentRef));
        }

        @Test
        @DisplayName("an anonymous attempt is recorded too")
        void anonymousAttemptsAreAudited() throws Exception {
            callers.anonymous();
            mvc.perform(get("/c/" + contentRef));

            assertThat(auditor.entries()).anySatisfy(entry ->
                    assertThat(entry).contains("ANONYMOUS"));
        }

        @Test
        @DisplayName("audit entries carry identifiers, never content")
        void auditCarriesNoContent() throws Exception {
            acl.grant(Permission.VIEW, Permission.DOWNLOAD);
            mvc.perform(get("/c/" + contentRef));

            assertThat(auditor.entries().toString())
                    .doesNotContain("AWS_SECRET")
                    .doesNotContain(FILENAME);
        }
    }

    static class TestBeans {

        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-11T09:41:12Z"), ZoneOffset.UTC);
        }

        InMemoryTicketRepository ticketRepository() {
            return new InMemoryTicketRepository();
        }

        InMemoryContentMetadataRepository contentMetadata() {
            return new InMemoryContentMetadataRepository();
        }

        ContentService contentService(InMemoryContentMetadataRepository metadata, Clock clock)
                throws Exception {
            Path root = Files.createTempDirectory("jvault-api-test");
            var kms = LocalKeyManagementService.withKeyRings("sec-restricted");
            return new ContentService(new ContentCipher(kms, 4096),
                    new FilesystemContentStore("fs-local", root), metadata,
                    ContentService.IdGenerator.random(), clock, "acme", root.resolve("spool"));
        }

        TestAcl acl() {
            return new TestAcl();
        }

        TestJira jira() {
            return new TestJira();
        }

        ContentAuthorizationService authorization(TestAcl acl, TestJira jira, Clock clock) {
            return new ContentAuthorizationService(acl, jira,
                    new ContentAuthorizationService.SpaceSettings() {
                        @Override
                        public SpacePermissionMode modeOf(String spaceId) {
                            return SpacePermissionMode.INTERSECT;
                        }

                        @Override
                        public Duration degradedGrace(String spaceId) {
                            return Duration.ZERO;
                        }
                    }, clock);
        }

        MutableCallerResolver callerResolver() {
            return new MutableCallerResolver();
        }

        RecordingAuditor auditor() {
            return new RecordingAuditor();
        }

        /** Grants apply at the space, which is how administrators normally describe access. */
        static final class TestAcl implements ContentAuthorizationService.AclRepository {

            private final List<Grant> grants = new ArrayList<>();

            void grant(Permission... permissions) {
                grants.add(Grant.of(Scope.space(SPACE_ID), RESPONDERS, Set.of(permissions)));
            }

            void reset() {
                grants.clear();
            }

            @Override
            public List<Grant> grantsAt(Scope scope) {
                return grants.stream()
                        .filter(g -> g.scope().type() == scope.type()
                                && g.scope().id().equals(scope.id()))
                        .toList();
            }

            @Override
            public boolean hasInheritanceBreak(Scope scope) {
                return false;
            }
        }

        static final class TestJira implements ContentAuthorizationService.JiraAccessChecker {

            private final AtomicReference<Access> access = new AtomicReference<>(Access.ALLOWED);

            void allow(boolean allowed) {
                access.set(allowed ? Access.ALLOWED : Access.DENIED);
            }

            void notConnected() {
                access.set(Access.NOT_CONNECTED);
            }

            void unavailable() {
                access.set(Access.UNAVAILABLE);
            }

            @Override
            public Access canBrowse(Principal user, Scope scope) {
                return access.get();
            }

            @Override
            public boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since) {
                return false;
            }
        }

        static final class MutableCallerResolver implements CallerResolver {

            private final AtomicReference<Caller> caller = new AtomicReference<>();

            void as(Caller caller) {
                this.caller.set(caller);
            }

            void anonymous() {
                caller.set(null);
            }

            @Override
            public Optional<Caller> resolve(jakarta.servlet.http.HttpServletRequest request) {
                return Optional.ofNullable(caller.get());
            }
        }

        static final class RecordingAuditor implements ContentAccessController.AccessAuditor {

            private final List<String> entries = new ArrayList<>();
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public void record(Caller caller, String contentRef, Permission permission,
                               String reason) {
                count.incrementAndGet();
                entries.add((caller == null ? "anonymous" : caller.toString())
                        + " " + permission + " " + contentRef + " " + reason);
            }

            List<String> entries() {
                return List.copyOf(entries);
            }

            void reset() {
                entries.clear();
            }
        }
    }
}
