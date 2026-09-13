package dev.jvault.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.api.error.ApiExceptionHandler;
import dev.jvault.api.idempotency.IdempotencyService;
import dev.jvault.api.idempotency.InMemoryIdempotencyStore;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.api.tickets.TicketController;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Grant;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.SpacePermissionMode;
import dev.jvault.content.ContentService;
import dev.jvault.content.LinkFactory;
import dev.jvault.content.TicketCreationService;
import dev.jvault.content.support.InMemoryContentMetadataRepository;
import dev.jvault.content.support.InMemoryOutboxRepository;
import dev.jvault.content.support.InMemoryTicketRepository;
import dev.jvault.crypto.envelope.ContentCipher;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.placement.LinkPlacement;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.Placement;
import dev.jvault.domain.placement.PlacementPolicy;
import dev.jvault.domain.placement.PolicySelector;
import dev.jvault.domain.placement.PolicySet;
import dev.jvault.domain.placement.SurrogateSpec;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating and reading tickets over HTTP.
 *
 * <p>Built on the real stack — policy engine, envelope encryption, filesystem storage, outbox —
 * with only the caller and the Jira transport substituted. What these assert about placement and
 * leakage is therefore what the system actually does, not what a mock was told to say.
 */
class TicketControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SECRET =
            "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String DEPLOYMENT = "jira-cloud-prod";
    private static final String SPACE = DEPLOYMENT + "/SEC";

    private static final Principal ALICE = Principal.user("alice");
    private static final Principal RESPONDERS = Principal.group("sec-responders");

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-13T09:41:12Z"), ZoneOffset.UTC);
    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final InMemoryContentMetadataRepository metadata = new InMemoryContentMetadataRepository();
    private final List<Grant> grants = new ArrayList<>();
    private final AtomicReference<Caller> caller = new AtomicReference<>();
    private final AtomicReference<ContentAuthorizationService.JiraAccessChecker.Access> jiraAccess =
            new AtomicReference<>();

    private MockMvc mvc;
    private Path storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Files.createTempDirectory("jvault-api-tickets");
        grants.clear();
        caller.set(new Caller(ALICE, Set.of(RESPONDERS), true));
        jiraAccess.set(ContentAuthorizationService.JiraAccessChecker.Access.ALLOWED);

        var kms = LocalKeyManagementService.withKeyRings("sec-restricted");
        var contentService = new ContentService(new ContentCipher(kms, 4096),
                new FilesystemContentStore("fs-local", storageRoot), metadata,
                ContentService.IdGenerator.random(), clock, "acme", storageRoot.resolve("spool"));
        var links = new LinkFactory("https://jvault.example.com");
        var creation = new TicketCreationService(policies(), contentService, tickets, outbox,
                links, ContentService.IdGenerator.random(), clock);
        var authorization = new ContentAuthorizationService(
                acl(), jiraChecker(), spaceSettings(), clock);

        var controller = new TicketController(creation, tickets, metadata, authorization,
                callerResolver(),
                new IdempotencyService(new InMemoryIdempotencyStore(), clock), links);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("creating a ticket")
    class Creating {

        @Test
        @DisplayName("returns 202 with a location, because the Jira write is queued")
        void acceptsAndQueues() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);

            String body = mvc.perform(post("/api/v1/tickets")
                            .contentType("application/json")
                            .content(createRequest()))
                    .andExpect(status().isAccepted())
                    .andExpect(header().exists("Location"))
                    .andReturn().getResponse().getContentAsString();

            // 201 would claim an issue exists; it does not yet, and blocking until Jira replied
            // would hand Jira's latency straight to the caller.
            assertThat(JSON.readTree(body).get("state").asText()).isEqualTo("JIRA_PENDING");
            assertThat(outbox.all()).isNotEmpty();
        }

        @Test
        @DisplayName("the sensitive field is externalised and the response links to it")
        void externalisesAndLinks() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);

            String body = mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()))
                    .andReturn().getResponse().getContentAsString();

            var response = JSON.readTree(body);
            assertThat(response.at("/jiraFields/description").asText())
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .contains("https://jvault.example.com/c/");
            assertThat(response.at("/parts/0/classification").asText()).isEqualTo("RESTRICTED");
            assertThat(body).doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("a missing summary is refused by name, without quoting the request")
        void validatesRequiredFields() throws Exception {
            grant(Permission.CREATE);

            String body = mvc.perform(post("/api/v1/tickets")
                            .contentType("application/json")
                            .content("""
                                    {"deploymentId":"jira-cloud-prod","projectKey":"SEC",
                                     "issueTypeId":"10004","fields":{"description":"%s"}}"""
                                    .formatted(SECRET)))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("fields.summary").contains("REQUIRED");
            // The body that failed validation carried a credential in another field.
            assertThat(body).doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("a malformed body is refused without echoing it")
        void malformedBodyIsNotEchoed() throws Exception {
            grant(Permission.CREATE);

            String body = mvc.perform(post("/api/v1/tickets")
                            .contentType("application/json")
                            .content("{\"projectKey\": \"SEC\", " + SECRET))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            // Jackson's own message quotes the offending input, and the offending input is the
            // request body.
            assertThat(body).doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("an anonymous caller gets 401")
        void anonymousIsRefused() throws Exception {
            caller.set(null);

            mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a caller without CREATE is refused, and nothing is stored")
        void requiresCreatePermission() throws Exception {
            grant(Permission.VIEW);

            mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()))
                    .andExpect(status().isForbidden());

            assertThat(outbox.all()).isEmpty();
            assertThat(metadata.partsOf("any")).isEmpty();
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("replaying a key returns the original response and creates nothing new")
        void replayReturnsTheOriginal() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);

            String first = mvc.perform(post("/api/v1/tickets")
                            .header("Idempotency-Key", "abc-123")
                            .contentType("application/json").content(createRequest()))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            int outboxAfterFirst = outbox.all().size();

            String second = mvc.perform(post("/api/v1/tickets")
                            .header("Idempotency-Key", "abc-123")
                            .contentType("application/json").content(createRequest()))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotency-Replayed", "true"))
                    .andReturn().getResponse().getContentAsString();

            // The client cannot tell which attempt produced it, which is the point.
            assertThat(second).isEqualTo(first);
            assertThat(outbox.all()).hasSize(outboxAfterFirst);
        }

        @Test
        @DisplayName("the same key with a different body is refused")
        void conflictingFingerprintIsRefused() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);
            mvc.perform(post("/api/v1/tickets")
                    .header("Idempotency-Key", "abc-123")
                    .contentType("application/json").content(createRequest()));

            String body = mvc.perform(post("/api/v1/tickets")
                            .header("Idempotency-Key", "abc-123")
                            .contentType("application/json")
                            .content(createRequest("A different summary")))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();

            // Almost always a client bug — a key reused across a loop — and serving the first
            // response for a different request would be far worse than saying so.
            assertThat(body).contains("idempotency-key-reuse");
        }

        @Test
        @DisplayName("keys are scoped to the caller")
        void keysAreScopedPerCaller() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);
            mvc.perform(post("/api/v1/tickets")
                    .header("Idempotency-Key", "retry-1")
                    .contentType("application/json").content(createRequest()));

            // "retry-1" is the sort of key two clients pick independently. One must not be able
            // to read the other's response.
            caller.set(new Caller(Principal.user("bob"), Set.of(RESPONDERS), true));

            mvc.perform(post("/api/v1/tickets")
                            .header("Idempotency-Key", "retry-1")
                            .contentType("application/json").content(createRequest()))
                    .andExpect(status().isAccepted())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"));
        }

        @Test
        @DisplayName("without a key, two identical posts create two tickets")
        void noKeyMeansNoProtection() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);

            mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()));
            mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()));

            // Stated rather than assumed: the key is what buys the protection, and a caller who
            // omits it gets the ordinary HTTP guarantee, which is none.
            assertThat(tickets.findByDedupeKey(DEPLOYMENT, "SEC", "alert-1")).isPresent();
            assertThat(outbox.all()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("reading a ticket")
    class Reading {

        @Test
        @DisplayName("an authorized caller sees the ticket and its part links")
        void readsBack() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);
            String created = mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()))
                    .andReturn().getResponse().getContentAsString();
            String ref = JSON.readTree(created).get("ticketRef").asText();

            String body = mvc.perform(get("/api/v1/tickets/" + ref))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(JSON.readTree(body).get("ticketRef").asText()).isEqualTo(ref);
            assertThat(body).doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("an outsider cannot distinguish a real ticket from an invented one")
        void outsidersGetTheSame404() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);
            String created = mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()))
                    .andReturn().getResponse().getContentAsString();
            String ref = JSON.readTree(created).get("ticketRef").asText();

            caller.set(new Caller(Principal.user("mallory"), Set.of(), true));

            int real = mvc.perform(get("/api/v1/tickets/" + ref))
                    .andReturn().getResponse().getStatus();
            int invented = mvc.perform(get("/api/v1/tickets/does-not-exist"))
                    .andReturn().getResponse().getStatus();

            assertThat(real).isEqualTo(404).isEqualTo(invented);
        }

        @Test
        @DisplayName("a Jira outage says the check cannot be made, not that access was lost")
        void jiraOutageIs503() throws Exception {
            grant(Permission.CREATE, Permission.VIEW);
            String created = mvc.perform(post("/api/v1/tickets")
                    .contentType("application/json").content(createRequest()))
                    .andReturn().getResponse().getContentAsString();
            String ref = JSON.readTree(created).get("ticketRef").asText();

            jiraAccess.set(ContentAuthorizationService.JiraAccessChecker.Access.UNAVAILABLE);

            mvc.perform(get("/api/v1/tickets/" + ref))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    // --- fixtures ----------------------------------------------------------------

    private static String createRequest() {
        return createRequest("[HIGH] Unexpected outbound connection");
    }

    private static String createRequest(String summary) {
        return """
                {
                  "deploymentId": "jira-cloud-prod",
                  "projectKey": "SEC",
                  "issueTypeId": "10004",
                  "dedupeKey": "alert-1",
                  "correlationId": "corr-1",
                  "fields": {"summary": "%s", "description": "%s"}
                }""".formatted(summary, SECRET);
    }

    private void grant(Permission... permissions) {
        grants.add(Grant.of(Scope.space(SPACE), RESPONDERS, Set.of(permissions)));
    }

    private PolicySet policies() {
        return PolicySet.of(List.of(PlacementPolicy.builder()
                .id("sec-description")
                .selector(new PolicySelector(DEPLOYMENT, "SEC", null, PartType.DESCRIPTION, null))
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .storageRoute("fs-local")
                .keyRing("sec-restricted")
                .encryptionRequired(true)
                .surrogate(SurrogateSpec.placeholder("Stored in jvault: {{link}}"))
                .linkPlacements(Set.of(LinkPlacement.DESCRIPTION_PLACEHOLDER,
                        LinkPlacement.REMOTE_LINK))
                .build()));
    }

    private ContentAuthorizationService.AclRepository acl() {
        return new ContentAuthorizationService.AclRepository() {
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
        };
    }

    private ContentAuthorizationService.JiraAccessChecker jiraChecker() {
        return new ContentAuthorizationService.JiraAccessChecker() {
            @Override
            public Access canBrowse(Principal user, Scope scope) {
                return jiraAccess.get();
            }

            @Override
            public boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since) {
                return false;
            }
        };
    }

    private ContentAuthorizationService.SpaceSettings spaceSettings() {
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

    private CallerResolver callerResolver() {
        return new CallerResolver() {
            @Override
            public Optional<Caller> resolve(HttpServletRequest request) {
                return Optional.ofNullable(caller.get());
            }
        };
    }
}
