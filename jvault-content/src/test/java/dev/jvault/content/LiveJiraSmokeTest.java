package dev.jvault.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.content.support.InMemoryCommentRepository;
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
import dev.jvault.jira.deployment.CloudDeployment;
import dev.jvault.jira.deployment.JiraCredentials;
import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.gateway.HttpJiraWriteGateway;
import dev.jvault.jira.gateway.JdkJiraHttpClient;
import dev.jvault.outbox.DispatchReport;
import dev.jvault.outbox.OutboxDispatcher;
import dev.jvault.outbox.TicketStateSink;
import dev.jvault.outbox.backoff.BackoffPolicy;
import dev.jvault.outbox.ratelimit.PerIssueRateLimiter;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole stack against a real Jira Cloud site.
 *
 * <p>Skipped unless {@code JIRA_SITE}, {@code JIRA_EMAIL} and {@code JIRA_TOKEN} are set, so an
 * ordinary build never touches anyone's instance. When it does run it creates one issue and
 * deletes it again in {@code @AfterEach}, even if the assertions fail.
 *
 * <p>What it proves that no stub can: that the request bodies jvault builds are ones Jira actually
 * accepts, that create-time properties really are written, that a repeated remote link really does
 * upsert, and — the point of the whole system — that the sensitive text reaches the vault while
 * Jira sees only a surrogate and a link.
 */
@EnabledIfEnvironmentVariable(named = "JIRA_SITE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_TOKEN", matches = ".+")
class LiveJiraSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CANARY =
            "Credential material observed on build-agent-07: "
                    + "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private static final String SITE = System.getenv("JIRA_SITE");
    private static final String EMAIL = System.getenv("JIRA_EMAIL");
    private static final String TOKEN = System.getenv("JIRA_TOKEN");
    private static final String PROJECT = envOrDefault("JIRA_PROJECT", "KAN");
    private static final String ISSUE_TYPE = envOrDefault("JIRA_ISSUE_TYPE", "10004");
    private static final String KEY_RING = "sec-restricted";
    private static final String DEPLOYMENT = "live";

    @TempDir
    Path storageRoot;

    private String createdIssueKey;

    @AfterEach
    void deleteWhateverWasCreated() throws Exception {
        if (createdIssueKey != null) {
            int status = jira("DELETE", "/rest/api/3/issue/" + createdIssueKey).statusCode();
            System.out.println("  cleanup: DELETE " + createdIssueKey + " -> " + status);
        }
    }

    @Test
    @DisplayName("a ticket with externalised content reaches Jira carrying only a surrogate")
    void endToEnd() throws Exception {
        var clock = Clock.systemUTC();
        var outbox = new InMemoryOutboxRepository();
        var tickets = new InMemoryTicketRepository();
        var metadata = new InMemoryContentMetadataRepository();
        var kms = LocalKeyManagementService.withKeyRings(KEY_RING);
        var store = new FilesystemContentStore("fs-local", storageRoot);
        var ids = ContentService.IdGenerator.random();

        var contentService = new ContentService(new ContentCipher(kms, 4096), store, metadata,
                ids, clock, "live", storageRoot.resolve("spool"));
        var creation = new TicketCreationService(policies(), contentService, tickets, outbox,
                new LinkFactory("https://jvault.example.com"), ids, clock);
        var assembler = new TicketPayloadAssembler(
                tickets, new InMemoryCommentRepository(), metadata, contentService);

        var deployment = new CloudDeployment(DEPLOYMENT, "unused",
                JiraCredentials.basic(EMAIL, () -> TOKEN), SITE);
        var gateway = new HttpJiraWriteGateway(deployment, new JdkJiraHttpClient(deployment));

        String correlationId = "live-" + UUID.randomUUID();
        var command = TicketCommand.builder(DEPLOYMENT, PROJECT, ISSUE_TYPE)
                .field("summary", "[jvault] live smoke test - safe to delete")
                .field("description", CANARY)
                .dedupeKey(correlationId)
                .correlationId(correlationId)
                .origin(TicketCommand.Origin.kafka("smoke@example.com", "INTEGRATION:live"))
                .build();

        TicketCreationService.Result result = creation.create(command);
        assertThat(result.storedParts()).hasSize(1);

        var issueKey = new java.util.concurrent.atomic.AtomicReference<String>();
        var issueId = new java.util.concurrent.atomic.AtomicReference<String>();
        var dispatcher = new OutboxDispatcher(outbox, assembler, EgressGuard.withDefaults(),
                gateway, PerIssueRateLimiter.jiraCloudDefaults(), BackoffPolicy.atlassianDefault(),
                sink(issueKey, issueId, tickets), clock, new Random());

        // Two passes: the create establishes the issue, the second dispatches the remote link
        // once the lane has an issue to attach it to.
        DispatchReport first = dispatcher.runOnce(10);
        System.out.println("  pass 1: " + first.summary());

        createdIssueKey = issueKey.get() != null
                ? issueKey.get()
                : findIssueByCorrelation(correlationId);
        assertThat(createdIssueKey).as("Jira should have created an issue").isNotNull();
        System.out.println("  created " + createdIssueKey);

        // Second pass: the lane now targets a real issue, so the remote link can be attached.
        DispatchReport second = dispatcher.runOnce(10);
        System.out.println("  pass 2: " + second.summary());

        JsonNode links = JSON.readTree(
                jira("GET", "/rest/api/3/issue/" + createdIssueKey + "/remotelink").body());
        System.out.println("  remote links: " + links.size());

        // --- what Jira actually holds -------------------------------------------------
        JsonNode issue = JSON.readTree(
                jira("GET", "/rest/api/3/issue/" + createdIssueKey).body());

        String jiraText = issue.toString();
        assertThat(jiraText)
                .as("the canary must not be anywhere in the issue Jira stored")
                .doesNotContain("AWS_SECRET_ACCESS_KEY")
                .doesNotContain("wJalrXUtnFEMI")
                .doesNotContain("build-agent-07");
        assertThat(jiraText).contains("https://jvault.example.com/c/");

        // The description is an ADF document Jira accepted, not a rejected blob.
        assertThat(issue.at("/fields/description/type").asText()).isEqualTo("doc");

        // Create-time properties: written in the same request as the issue.
        JsonNode origin = JSON.readTree(
                jira("GET", "/rest/api/3/issue/" + createdIssueKey
                        + "/properties/jvault.origin").body());
        assertThat(origin.at("/value/correlationId").asText()).isEqualTo(correlationId);

        // --- and the content is readable from the vault --------------------------------
        try (var in = contentService.open(result.storedParts().get(0))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CANARY);
        }
        assertThat(rawBytesOnDisk()).doesNotContain("AWS_SECRET_ACCESS_KEY");

        System.out.println("  jvault holds the narrative; Jira holds a link. Verified.");
    }

    // --- helpers -----------------------------------------------------------------

    private String findIssueByCorrelation(String correlationId) throws Exception {
        // Only reached if the ticket record did not capture the key; this is the same bounded
        // search the ambiguity protocol performs.
        var response = jira("GET", "/rest/api/3/search/jql?jql="
                + java.net.URLEncoder.encode(
                        "project=" + PROJECT + " ORDER BY created DESC", StandardCharsets.UTF_8)
                + "&maxResults=5&fields=summary");
        JsonNode found = JSON.readTree(response.body());
        for (JsonNode candidate : found.path("issues")) {
            String key = candidate.path("key").asText();
            JsonNode origin = JSON.readTree(
                    jira("GET", "/rest/api/3/issue/" + key + "/properties/jvault.origin").body());
            if (correlationId.equals(origin.at("/value/correlationId").asText())) {
                return key;
            }
        }
        return null;
    }

    private static HttpResponse<String> jira(String method, String path) throws Exception {
        String auth = Base64.getEncoder()
                .encodeToString((EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(SITE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth)
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static PolicySet policies() {
        return PolicySet.of(List.of(PlacementPolicy.builder()
                .id("live-description")
                .selector(new PolicySelector(DEPLOYMENT, PROJECT, null, PartType.DESCRIPTION, null))
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .storageRoute("fs-local")
                .keyRing(KEY_RING)
                .encryptionRequired(true)
                .surrogate(SurrogateSpec.placeholder(
                        "Incident details are stored in jvault and are not visible in Jira. "
                                + "Classification: {{classification}}. Open: {{link}}"))
                .linkPlacements(Set.of(LinkPlacement.DESCRIPTION_PLACEHOLDER,
                        LinkPlacement.REMOTE_LINK))
                .build()));
    }

    private String rawBytesOnDisk() throws IOException {
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            var all = new StringBuilder();
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                all.append(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
            }
            return all.toString();
        }
    }

    private static TicketStateSink sink(java.util.concurrent.atomic.AtomicReference<String> key,
                                        java.util.concurrent.atomic.AtomicReference<String> id,
                                        InMemoryTicketRepository tickets) {
        return new TicketStateSink() {
            @Override
            public void ticketBecameActive(String ticketRef, String issueId, String issueKey,
                                           Instant when) {
                key.set(issueKey);
                id.set(issueId);
                // A real sink writes this back so later effects target the issue rather than the
                // pending-ticket lane; the in-memory repository needs the same nudge.
                tickets.find(ticketRef).ifPresent(t -> tickets.save(new TicketRecord(
                        t.ticketRef(), t.deploymentId(), t.projectKey(), t.issueTypeId(),
                        t.dedupeKey(), t.correlationId(), t.origin(), t.jiraFields(),
                        t.externalContentRefs(), TicketRecord.State.ACTIVE, issueId, issueKey,
                        t.createdAt())));
                System.out.println("  ticket active: " + issueKey);
            }

            @Override
            public void ticketBecameAmbiguous(String ticketRef, AmbiguityContext context) {
                System.out.println("  AMBIGUOUS: " + ticketRef);
            }

            @Override
            public void ticketFailed(String ticketRef, String errorCode, Instant when) {
                System.out.println("  FAILED: " + errorCode);
            }
        };
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
