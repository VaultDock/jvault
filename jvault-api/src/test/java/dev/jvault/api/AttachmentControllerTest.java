package dev.jvault.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.api.attachments.AttachmentController;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Grant;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.SpacePermissionMode;
import dev.jvault.content.ContentRecord;
import dev.jvault.content.LinkFactory;
import dev.jvault.content.TicketAmendmentService;
import dev.jvault.content.TicketCommand;
import dev.jvault.content.TicketRecord;
import dev.jvault.content.TicketRepository;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PartType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uploading a document.
 *
 * <p>The interesting assertions are about what does <em>not</em> happen: an unauthorized caller
 * cannot learn that a ticket exists, and a file that is too large is refused before anything is
 * written rather than after.
 */
class AttachmentControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1024;

    private final AtomicReference<Caller> caller = new AtomicReference<>();
    private final List<String> uploaded = new ArrayList<>();
    private final AtomicReference<RuntimeException> uploadFails = new AtomicReference<>();

    private MockMvc mvc;
    private InMemoryAcl acl;

    @BeforeEach
    void setUp() {
        caller.set(new Caller(Principal.user("alice"), Set.of(), true));
        uploaded.clear();
        uploadFails.set(null);

        acl = new InMemoryAcl();
        acl.allow(Scope.space("d1/KAN"), Principal.user("alice"),
                Set.of(Permission.VIEW, Permission.EDIT));

        var authorization = new ContentAuthorizationService(acl, allowJira(), settings(),
                Clock.systemUTC());
        var controller = new AttachmentController(amendments(), ticketRepository(), authorization,
                callerResolver(), new LinkFactory("http://localhost:8080"), MAX_BYTES);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("a document is stored and Jira is given a link rather than the file")
    void uploadStoresAndLinks() throws Exception {
        String body = mvc.perform(upload("quarterly.xlsx", "the actual bytes"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        var response = JSON.readTree(body);
        assertThat(response.get("fileName").asText()).isEqualTo("quarterly.xlsx");
        assertThat(response.get("link").asText()).contains("/c/");
        assertThat(response.get("classification").asText()).isEqualTo("RESTRICTED");
        assertThat(uploaded).containsExactly("quarterly.xlsx");
    }

    @Test
    @DisplayName("a file above the limit is refused before anything is written")
    void oversizedFilesAreRefused() throws Exception {
        byte[] tooBig = new byte[(int) MAX_BYTES + 1];

        mvc.perform(multipart("/api/v1/tickets/t-1/attachments")
                        .file(new MockMultipartFile("file", "big.bin", "application/octet-stream",
                                tooBig)))
                .andExpect(status().isPayloadTooLarge());

        // Discovering the limit after writing the ciphertext means having to clean it up.
        assertThat(uploaded).isEmpty();
    }

    @Test
    @DisplayName("an empty file is refused rather than stored as a zero-byte part")
    void emptyFilesAreRefused() throws Exception {
        mvc.perform(upload("empty.txt", "")).andExpect(status().isBadRequest());

        assertThat(uploaded).isEmpty();
    }

    @Test
    @DisplayName("a caller with no grant is told the ticket does not exist")
    void strangersLearnNothing() throws Exception {
        caller.set(new Caller(Principal.user("mallory"), Set.of(), true));

        // Not 403: a refusal that distinguishes a real ticket from an invented one is an
        // existence oracle for anyone who can guess references.
        mvc.perform(upload("probe.txt", "x")).andExpect(status().isNotFound());
        assertThat(uploaded).isEmpty();
    }

    @Test
    @DisplayName("a caller who may read but not edit cannot attach")
    void readersCannotAttach() throws Exception {
        acl.allow(Scope.space("d1/KAN"), Principal.user("bob"), Set.of(Permission.VIEW));
        caller.set(new Caller(Principal.user("bob"), Set.of(), true));

        mvc.perform(upload("notes.txt", "x")).andExpect(status().isForbidden());
        assertThat(uploaded).isEmpty();
    }

    @Test
    @DisplayName("an unknown ticket is a 404, not a stored orphan")
    void unknownTicketsAreRefused() throws Exception {
        mvc.perform(multipart("/api/v1/tickets/nope/attachments")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain",
                                "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a path in the filename does not survive into the stored name")
    void filenamesAreStrippedOfPaths() throws Exception {
        String body = mvc.perform(upload("../../etc/passwd", "x"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // jvault never uses this as a path — object keys are opaque by construction — so this is
        // a second line of defence rather than the only one.
        assertThat(JSON.readTree(body).get("fileName").asText()).isEqualTo("passwd");
    }

    @Test
    @DisplayName("a project whose policy keeps attachments in Jira is refused, not silently copied")
    void jiraPlacedAttachmentsAreRefused() throws Exception {
        uploadFails.set(new UnsupportedOperationException("Jira-placed attachments"));

        mvc.perform(upload("x.txt", "x")).andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("an anonymous caller gets 401")
    void anonymousIsRefused() throws Exception {
        caller.set(null);

        mvc.perform(upload("x.txt", "x")).andExpect(status().isUnauthorized());
    }

    // --- fixtures ----------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            upload(String name, String content) {
        return multipart("/api/v1/tickets/t-1/attachments")
                .file(new MockMultipartFile("file", name, "text/plain",
                        content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Mocked rather than assembled: the amendment service is final, and a test that needed it
     * opened up would be a test dictating the shape of production code.
     */
    private TicketAmendmentService amendments() {
        TicketAmendmentService service = org.mockito.Mockito.mock(TicketAmendmentService.class);
        org.mockito.Mockito.when(service.addAttachment(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    RuntimeException failure = uploadFails.get();
                    if (failure != null) {
                        throw failure;
                    }
                    String ticketRef = invocation.getArgument(0);
                    String fileName = invocation.getArgument(1);
                    String mediaType = invocation.getArgument(3);
                    long size = invocation.getArgument(4);
                    uploaded.add(fileName);

                    var stored = new ContentRecord(UUID.randomUUID().toString().replace("-", ""),
                            "v1", 1, ticketRef, PartType.ATTACHMENT, null,
                            Classification.RESTRICTED, "sec-restricted", "kek-1", new byte[16],
                            new byte[32], new byte[32], size, mediaType,
                            SensitiveValue.of(fileName, "attachment.fileName"),
                            "Held in jvault", null, Instant.now());
                    return new TicketAmendmentService.AttachmentResult(
                            stored, "Held in jvault", null);
                });
        return service;
    }

    private static TicketRepository ticketRepository() {
        var ticket = new TicketRecord("t-1", "d1", "KAN", "10004", null, null,
                TicketCommand.Origin.ui("alice", "Alice"), Map.of(), List.of(),
                TicketRecord.State.ACTIVE, null, null, Instant.now());
        return new TicketRepository() {
            @Override
            public Reservation reserve(TicketRecord candidate) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void save(TicketRecord record) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<TicketRecord> find(String ticketRef) {
                return "t-1".equals(ticketRef) ? Optional.of(ticket) : Optional.empty();
            }

            @Override
            public Optional<TicketRecord> findByDedupeKey(String d, String p, String k) {
                return Optional.empty();
            }
        };
    }

    private CallerResolver callerResolver() {
        return (HttpServletRequest request) -> Optional.ofNullable(caller.get());
    }

    private static ContentAuthorizationService.JiraAccessChecker allowJira() {
        return new ContentAuthorizationService.JiraAccessChecker() {
            @Override
            public Access canBrowse(Principal user, Scope scope) {
                return Access.ALLOWED;
            }

            @Override
            public boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since) {
                return true;
            }
        };
    }

    private static ContentAuthorizationService.SpaceSettings settings() {
        return new ContentAuthorizationService.SpaceSettings() {
            @Override
            public SpacePermissionMode modeOf(String spaceId) {
                return SpacePermissionMode.INTERSECT;
            }

            @Override
            public Duration degradedGrace(String spaceId) {
                return Duration.ZERO;
            }
        };
    }

    private static final class InMemoryAcl implements ContentAuthorizationService.AclRepository {
        private final List<Grant> grants = new ArrayList<>();

        void allow(Scope scope, Principal principal, Set<Permission> permissions) {
            grants.add(Grant.of(scope, principal, permissions));
        }

        @Override
        public List<Grant> grantsAt(Scope scope) {
            return grants.stream().filter(g -> g.scope().equals(scope)).toList();
        }

        @Override
        public boolean hasInheritanceBreak(Scope scope) {
            return false;
        }
    }
}
