package dev.jvault.content;

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
import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.jira.egress.JiraSafePayload;
import dev.jvault.jira.gateway.JiraWriteGateway;
import dev.jvault.outbox.DispatchReport;
import dev.jvault.outbox.OutboxDispatcher;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.TicketStateSink;
import dev.jvault.outbox.backoff.BackoffPolicy;
import dev.jvault.outbox.ratelimit.PerIssueRateLimiter;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole content path, with every real component except the Jira transport: policy engine,
 * envelope encryption, filesystem store, outbox, dispatcher and egress guard.
 *
 * <p>This is the test that makes the design's central claim checkable — that sensitive content
 * can be externalised without any of it reaching Jira — and it is the seed of the three-way
 * conformance suite (docs/06-rest-api.md 6.3), because the UI, REST and Kafka adapters all build
 * the same {@link TicketCommand} this test builds.
 */
class EndToEndTicketCreationTest {

    private static final String CANARY =
            "Credential material observed in the process environment: "
                    + "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY. "
                    + "Affected host build-agent-07, process /opt/ci/runner pid 21884.";

    private static final String DEPLOYMENT = "jira-cloud-prod";
    private static final String KEY_RING = "sec-restricted";

    @TempDir
    Path storageRoot;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T09:41:12Z"), ZoneOffset.UTC);
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final InMemoryContentMetadataRepository metadata = new InMemoryContentMetadataRepository();
    private final InMemoryCommentRepository comments = new InMemoryCommentRepository();

    private ContentService contentService;
    private TicketCreationService creation;
    private TicketPayloadAssembler assembler;
    private FilesystemContentStore store;

    @BeforeEach
    void setUp() {
        var kms = LocalKeyManagementService.withKeyRings(KEY_RING, "general");
        store = new FilesystemContentStore("fs-local", storageRoot);
        var ids = sequentialIds();

        contentService = new ContentService(new ContentCipher(kms, 4096), store, metadata,
                ids, clock, "acme");
        creation = new TicketCreationService(policies(), contentService, tickets, outbox,
                new LinkFactory("https://jvault.example.com"), ids, clock);
        assembler = new TicketPayloadAssembler(tickets, comments, metadata, contentService);
    }

    @Nested
    @DisplayName("creating a ticket with an externalised description")
    class Creation {

        @Test
        @DisplayName("the sensitive value is stored encrypted and never enters the Jira payload")
        void sensitiveContentNeverReachesJira() throws Exception {
            TicketCreationService.Result result = creation.create(incidentCommand());

            // 1. The content is in jvault, and readable.
            ContentRecord part = result.storedParts().stream()
                    .filter(p -> "description".equals(p.fieldKey()))
                    .findFirst().orElseThrow();
            assertThat(readBack(part)).isEqualTo(CANARY);

            // 2. What Jira will receive contains the surrogate, not the content.
            String description = result.ticket().jiraFields().get("description");
            assertThat(description)
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .doesNotContain("build-agent-07")
                    .contains("https://jvault.example.com/c/" + part.contentRef());

            // 3. The bytes on disk reveal nothing either.
            assertThat(rawBytesOnDisk())
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .doesNotContain("build-agent-07");

            // 4. Nothing anywhere in the outbox carries it.
            assertThat(outbox.all().toString()).doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("fields the policy keeps in Jira pass through untouched")
        void jiraPlacedFieldsAreVerbatim() {
            TicketCreationService.Result result = creation.create(incidentCommand());

            assertThat(result.ticket().jiraFields().get("summary"))
                    .isEqualTo("[HIGH] Unexpected outbound connection from build agent");
            assertThat(result.ticket().jiraFields().get("customfield_10010"))
                    .isEqualTo("build-agent-07");
        }

        @Test
        @DisplayName("the effects a ticket needs are enqueued, and only those")
        void enqueuesTheRightEffects() {
            TicketCreationService.Result result = creation.create(incidentCommand());

            assertThat(result.effects()).extracting(OutboxEntry::effectKey)
                    .containsExactly("create-issue", "prop:jvault.origin",
                            "remote-link:" + result.storedParts().get(0).contentRef());
            assertThat(result.effects()).extracting(OutboxEntry::operation)
                    .containsExactly(JiraOperation.CREATE_ISSUE, JiraOperation.SET_PROPERTY,
                            JiraOperation.UPSERT_REMOTE_LINK);
        }

        @Test
        @DisplayName("the create effect carries what the ambiguity protocol will need")
        void createEffectCarriesRecoveryContext() {
            TicketCreationService.Result result = creation.create(incidentCommand());

            OutboxEntry create = result.effects().get(0);
            assertThat(create.payloadRef())
                    .containsEntry("projectKey", "SEC")
                    .containsEntry("correlationId", "corr-8f21c")
                    .containsEntry("expectedSummary",
                            "[HIGH] Unexpected outbound connection from build agent");
            assertThat(create.payloadRef().toString())
                    .as("even the recovery context holds identifiers only")
                    .doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("the ticket ends ready for dispatch, with content already safe")
        void ticketReachesJiraPending() {
            TicketCreationService.Result result = creation.create(incidentCommand());

            assertThat(result.ticket().state()).isEqualTo(TicketRecord.State.JIRA_PENDING);
            assertThat(result.ticket().externalContentRefs()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("through the dispatcher to the gateway")
    class ThroughTheDispatcher {

        @Test
        @DisplayName("the payload the gateway receives contains surrogates only")
        void gatewaySeesOnlySurrogates() {
            creation.create(incidentCommand());

            var sent = new ArrayList<JiraSafePayload>();
            dispatcher(payload -> {
                sent.add(payload);
                return JiraWriteGateway.JiraWriteResult.succeeded("10001", "SEC-4471");
            }).runOnce(10);

            assertThat(sent).isNotEmpty();
            String everythingSent = sent.stream()
                    .flatMap(p -> p.textFields().values().stream())
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(everythingSent)
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .doesNotContain("wJalrXUtnFEMI")
                    .doesNotContain("build-agent-07 process");
            assertThat(everythingSent).contains("https://jvault.example.com/c/");
        }

        @Test
        @DisplayName("the guard actually ran on the real bytes, and says so")
        void guardRanOnTheRealPayload() {
            creation.create(incidentCommand());

            var sent = new ArrayList<JiraSafePayload>();
            dispatcher(payload -> {
                sent.add(payload);
                return JiraWriteGateway.JiraWriteResult.succeeded("10001", "SEC-4471");
            }).runOnce(1);

            // "content-hash" rather than "skipped": the assembler supplied the ticket's external
            // content, so the comparison had something to compare against.
            assertThat(sent.get(0).checksPerformed()).contains("content-hash");
        }

        @Test
        @DisplayName("a remote link is idempotent by globalId")
        void remoteLinkCarriesGlobalId() {
            TicketCreationService.Result result = creation.create(incidentCommand());
            String contentRef = result.storedParts().get(0).contentRef();

            var sent = new ArrayList<JiraSafePayload>();
            OutboxDispatcher dispatcher = dispatcher(payload -> {
                sent.add(payload);
                return JiraWriteGateway.JiraWriteResult.succeeded("10001", "SEC-4471");
            });
            dispatcher.runOnce(10);

            JiraSafePayload link = sent.stream()
                    .filter(p -> p.operation() == JiraOperation.UPSERT_REMOTE_LINK)
                    .findFirst().orElseThrow();

            assertThat(link.textFields().get("globalId")).isEqualTo("jvault:content:" + contentRef);
        }

        @Test
        @DisplayName("a whole dispatch pass reports every effect sent")
        void dispatchReportsAllEffects() {
            creation.create(incidentCommand());

            DispatchReport report = dispatcher(payload ->
                    JiraWriteGateway.JiraWriteResult.succeeded("10001", "SEC-4471")).runOnce(10);

            assertThat(report.count(DispatchReport.Disposition.SENT)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("duplicate prevention")
    class Duplicates {

        @Test
        @DisplayName("the same dedupe key creates one ticket, not two")
        void replayCreatesNothingNew() {
            TicketCreationService.Result first = creation.create(incidentCommand());
            TicketCreationService.Result second = creation.create(incidentCommand());

            assertThat(second.duplicate()).isTrue();
            assertThat(second.ticket().ticketRef()).isEqualTo(first.ticket().ticketRef());
            assertThat(second.storedParts())
                    .as("a replay must not re-encrypt and re-store the content either")
                    .isEmpty();
            assertThat(outbox.all()).hasSize(3);
        }

        @Test
        @DisplayName("a different dedupe key is a different ticket")
        void differentKeyIsDifferentTicket() {
            creation.create(incidentCommand());
            TicketCreationService.Result other = creation.create(
                    commandBuilder().dedupeKey("alert-8f21c|2").build());

            assertThat(other.duplicate()).isFalse();
            assertThat(outbox.all()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("placement policy governs, not the caller")
    class PolicyGoverns {

        @Test
        @DisplayName("a caller cannot pull externalised content back into Jira")
        void overrideCannotLoosenPlacement() {
            TicketCreationService.Result result = creation.create(
                    commandBuilder().override("description", Placement.JIRA).build());

            assertThat(result.storedParts())
                    .as("the description must still have been externalised")
                    .hasSize(1);
            assertThat(result.ticket().jiraFields().get("description"))
                    .doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("a caller may tighten placement where the policy permits it")
        void overrideCanTighten() {
            TicketCreationService.Result result = creation.create(
                    commandBuilder().override("customfield_10010", Placement.EXTERNAL).build());

            assertThat(result.storedParts()).extracting(ContentRecord::fieldKey)
                    .contains("customfield_10010");
        }
    }

    // --- fixtures ----------------------------------------------------------------

    private TicketCommand incidentCommand() {
        return commandBuilder().build();
    }

    private TicketCommand.Builder commandBuilder() {
        return TicketCommand.builder(DEPLOYMENT, "SEC", "10004")
                .field("summary", "[HIGH] Unexpected outbound connection from build agent")
                .field("description", CANARY)
                .field("customfield_10010", "build-agent-07")
                .dedupeKey("alert-8f21c|1")
                .correlationId("corr-8f21c")
                .origin(TicketCommand.Origin.kafka("soc-analyst@example.com",
                        "INTEGRATION:svc-jvault"));
    }

    private PolicySet policies() {
        return PolicySet.of(List.of(
                PlacementPolicy.builder()
                        .id("sec-incident-description")
                        .selector(new PolicySelector(DEPLOYMENT, "SEC", "10004",
                                PartType.DESCRIPTION, null))
                        .placement(Placement.EXTERNAL)
                        .classification(Classification.RESTRICTED)
                        .storageRoute("obj-dc1-restricted")
                        .keyRing(KEY_RING)
                        .encryptionRequired(true)
                        .surrogate(SurrogateSpec.placeholder(
                                "Incident details are stored in jvault and are not visible in "
                                        + "Jira. Classification: {{classification}}. Open: {{link}}"))
                        .linkPlacements(Set.of(LinkPlacement.DESCRIPTION_PLACEHOLDER,
                                LinkPlacement.REMOTE_LINK))
                        .allowOverride(false)
                        .build(),
                PlacementPolicy.builder()
                        .id("sec-project-default")
                        .selector(new PolicySelector(DEPLOYMENT, "SEC", null, null, null))
                        .placement(Placement.JIRA)
                        .classification(Classification.CONFIDENTIAL)
                        .storageRoute("obj-dc1-internal")
                        .keyRing(KEY_RING)
                        .encryptionRequired(true)
                        .surrogate(SurrogateSpec.placeholder("Stored in jvault: {{link}}"))
                        .linkPlacements(Set.of(LinkPlacement.REMOTE_LINK))
                        .allowOverride(true)
                        .build()));
    }

    private OutboxDispatcher dispatcher(JiraWriteGateway gateway) {
        return new OutboxDispatcher(outbox, assembler, EgressGuard.withDefaults(), gateway,
                PerIssueRateLimiter.jiraCloudDefaults(), BackoffPolicy.atlassianDefault(),
                noOpTicketStates(), clock, new java.util.Random(42));
    }

    private String readBack(ContentRecord part) throws IOException {
        try (InputStream in = contentService.open(part)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    private static ContentService.IdGenerator sequentialIds() {
        var counter = new AtomicInteger();
        return new ContentService.IdGenerator() {
            @Override
            public String newContentRef() {
                return "ref" + String.format("%08d", counter.incrementAndGet());
            }

            @Override
            public String newVersionId() {
                return "ver" + String.format("%08d", counter.incrementAndGet());
            }
        };
    }

    private static TicketStateSink noOpTicketStates() {
        return new TicketStateSink() {
            @Override
            public void ticketBecameActive(String ticketRef, String issueId, String issueKey,
                                           Instant when) {
            }

            @Override
            public void ticketBecameAmbiguous(String ticketRef, AmbiguityContext context) {
            }

            @Override
            public void ticketFailed(String ticketRef, String errorCode, Instant when) {
            }
        };
    }
}
