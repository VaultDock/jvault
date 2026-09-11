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
import dev.jvault.outbox.JiraPayloadAssembler;
import dev.jvault.outbox.OutboxDispatcher;
import dev.jvault.outbox.TicketStateSink;
import dev.jvault.outbox.backoff.BackoffPolicy;
import dev.jvault.outbox.ratelimit.PerIssueRateLimiter;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comments and attachments, which differ from ordinary fields in ways worth pinning down: a
 * comment keeps a Jira shell even when its body is externalised, and an attachment reaches Jira
 * in no form at all.
 */
class CommentsAndAttachmentsTest {

    private static final String DEPLOYMENT = "jira-cloud-prod";
    private static final String KEY_RING = "sec-restricted";

    private static final String COMMENT_BODY =
            "Rotated the exposed key. New value is AWS_SECRET_ACCESS_KEY="
                    + "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY, stored in the vault.";

    private static final String EVIDENCE_FILENAME = "2026-Q3-incident-evidence.pcap";

    @TempDir
    Path storageRoot;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T09:41:12Z"), ZoneOffset.UTC);
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final InMemoryContentMetadataRepository metadata = new InMemoryContentMetadataRepository();
    private final InMemoryCommentRepository comments = new InMemoryCommentRepository();

    private ContentService contentService;
    private TicketAmendmentService amendments;
    private TicketPayloadAssembler assembler;
    private FilesystemContentStore store;
    private String ticketRef;

    @BeforeEach
    void setUp() {
        var kms = LocalKeyManagementService.withKeyRings(KEY_RING);
        store = new FilesystemContentStore("fs-local", storageRoot);
        var ids = sequentialIds();

        contentService = new ContentService(new ContentCipher(kms, 4096), store, metadata,
                ids, clock, "acme", storageRoot.resolve("spool"));
        amendments = new TicketAmendmentService(policies(), contentService, tickets, comments,
                outbox, new LinkFactory("https://jvault.example.com"), ids, clock);
        assembler = new TicketPayloadAssembler(tickets, comments, metadata, contentService);

        ticketRef = "ticket0001";
        tickets.reserve(new TicketRecord(ticketRef, DEPLOYMENT, "SEC", "10004", null, "corr-1",
                TicketCommand.Origin.kafka("soc@example.com", "INTEGRATION:svc-jvault"),
                Map.of("summary", "Incident"), List.of(),
                TicketRecord.State.ACTIVE, "10001", "SEC-4471", clock.instant()));
    }

    @Nested
    @DisplayName("comments")
    class Comments {

        @Test
        @DisplayName("an externalised body still produces a real Jira comment")
        void externalCommentKeepsItsJiraShell() {
            TicketAmendmentService.CommentResult result =
                    amendments.addComment(ticketRef, COMMENT_BODY, "soc@example.com");

            // Without the shell the conversation has holes and Jira's notifications, watchers
            // and mentions silently stop working for this comment.
            assertThat(result.effect().operation()).isEqualTo(JiraOperation.ADD_COMMENT);
            assertThat(result.comment().jiraBody())
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .contains("https://jvault.example.com/c/" + result.storedBody().contentRef());
        }

        @Test
        @DisplayName("the body is stored encrypted and reads back intact")
        void bodyIsStoredAndRecoverable() throws Exception {
            TicketAmendmentService.CommentResult result =
                    amendments.addComment(ticketRef, COMMENT_BODY, "soc@example.com");

            assertThat(readBack(result.storedBody())).isEqualTo(COMMENT_BODY);
            assertThat(rawBytesOnDisk()).doesNotContain("wJalrXUtnFEMI");
        }

        @Test
        @DisplayName("what the gateway receives is the surrogate, not the body")
        void gatewaySeesTheSurrogate() {
            amendments.addComment(ticketRef, COMMENT_BODY, "soc@example.com");

            JiraSafePayload comment = dispatchAll().stream()
                    .filter(p -> p.operation() == JiraOperation.ADD_COMMENT)
                    .findFirst().orElseThrow();

            assertThat(comment.textFields().get("body"))
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .doesNotContain("wJalrXUtnFEMI")
                    .contains("https://jvault.example.com/c/");
        }

        @Test
        @DisplayName("a comment whose policy keeps it in Jira is sent verbatim")
        void jiraPlacedCommentIsVerbatim() {
            tickets.save(engTicket("ticket0002", "10002", "ENG-1"));

            TicketAmendmentService.CommentResult result = amendments.addComment(
                    "ticket0002", "Deploy finished cleanly.", "dev@example.com");

            assertThat(result.storedBody()).isNull();
            assertThat(result.comment().jiraBody()).isEqualTo("Deploy finished cleanly.");
            assertThat(result.comment().isExternallyStored()).isFalse();
        }

        @Test
        @DisplayName("a deleted comment makes its effect moot rather than failing")
        void deletedCommentIsNoLongerApplicable() {
            var result = amendments.addComment(ticketRef, COMMENT_BODY, "soc@example.com");
            var isolated = new TicketPayloadAssembler(
                    tickets, new InMemoryCommentRepository(), metadata, contentService);

            assertThatThrownBy(() -> isolated.assemble(result.effect()))
                    .isInstanceOf(JiraPayloadAssembler.EffectNoLongerApplicable.class)
                    .hasMessageContaining("COMMENT_DELETED");
        }
    }

    @Nested
    @DisplayName("attachments")
    class Attachments {

        @Test
        @DisplayName("the bytes never reach Jira in any form, only a remote link")
        void attachmentNeverReachesJira() {
            byte[] evidence = "PCAP binary evidence with build-agent-07 inside"
                    .getBytes(StandardCharsets.UTF_8);

            TicketAmendmentService.AttachmentResult result = amendments.addAttachment(
                    ticketRef, EVIDENCE_FILENAME, new ByteArrayInputStream(evidence),
                    "application/vnd.tcpdump.pcap", evidence.length);

            assertThat(result.effect().operation()).isEqualTo(JiraOperation.UPSERT_REMOTE_LINK);

            List<JiraSafePayload> sent = dispatchAll();
            assertThat(everythingSent(sent))
                    .doesNotContain("binary evidence")
                    .doesNotContain("build-agent-07");
            assertThat(sent).noneMatch(p -> p.operation() == JiraOperation.ADD_ATTACHMENT);
        }

        @Test
        @DisplayName("the filename never leaves jvault")
        void filenameIsNeverExposed() throws Exception {
            TicketAmendmentService.AttachmentResult result = amendments.addAttachment(
                    ticketRef, EVIDENCE_FILENAME,
                    new ByteArrayInputStream("x".repeat(500).getBytes(StandardCharsets.UTF_8)),
                    "application/vnd.tcpdump.pcap", 500);

            // Not in the surrogate, not in the storage path, not on disk, not in the link title.
            assertThat(result.surrogate()).doesNotContain(EVIDENCE_FILENAME);
            assertThat(result.stored().storedObject().key().asPath()).doesNotContain("incident");
            assertThat(rawBytesOnDisk()).doesNotContain(EVIDENCE_FILENAME);

            String linkTitle = dispatchAll().stream()
                    .filter(p -> p.operation() == JiraOperation.UPSERT_REMOTE_LINK)
                    .findFirst().orElseThrow()
                    .textFields().get("title");
            assertThat(linkTitle).doesNotContain(EVIDENCE_FILENAME).contains("attachment");
        }

        @Test
        @DisplayName("the filename is a sensitive value, so it cannot be logged by accident")
        void filenameIsMarkedSensitive() {
            TicketAmendmentService.AttachmentResult result = amendments.addAttachment(
                    ticketRef, EVIDENCE_FILENAME,
                    new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                    "application/octet-stream", 1);

            assertThat(result.stored().displayName().reveal()).isEqualTo(EVIDENCE_FILENAME);
            assertThat(result.stored().displayName().toString()).doesNotContain(EVIDENCE_FILENAME);
            assertThat(result.stored().toString()).doesNotContain(EVIDENCE_FILENAME);
        }

        @Test
        @DisplayName("a large attachment streams through a spool rather than through memory")
        void largeAttachmentStreams() throws Exception {
            int size = 8 * 1024 * 1024;

            TicketAmendmentService.AttachmentResult result = amendments.addAttachment(
                    ticketRef, "big.bin", repeating(size), "application/octet-stream", size);

            assertThat(result.stored().sizeBytes()).isEqualTo(size);
            // The spool holds ciphertext and is emptied once the object is in the store.
            try (Stream<Path> spooled = Files.list(storageRoot.resolve("spool"))) {
                assertThat(spooled).isEmpty();
            }
        }

        @Test
        @DisplayName("a policy that would put attachments in Jira is refused, not quietly honoured")
        void jiraPlacedAttachmentsAreRefused() {
            tickets.save(engTicket("ticket0003", "10003", "ENG-2"));

            assertThatThrownBy(() -> amendments.addAttachment("ticket0003", "notes.txt",
                    new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                    "text/plain", 5))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("not implemented yet");
        }
    }

    // --- fixtures ----------------------------------------------------------------

    private TicketRecord engTicket(String ref, String issueId, String issueKey) {
        return new TicketRecord(ref, DEPLOYMENT, "ENG", "10001", null, null, null,
                Map.of(), List.of(), TicketRecord.State.ACTIVE, issueId, issueKey, clock.instant());
    }

    private PolicySet policies() {
        return PolicySet.of(List.of(
                PlacementPolicy.builder()
                        .id("sec-comments")
                        .selector(new PolicySelector(DEPLOYMENT, "SEC", null, PartType.COMMENT, null))
                        .placement(Placement.EXTERNAL)
                        .classification(Classification.RESTRICTED)
                        .storageRoute("obj-dc1-restricted").keyRing(KEY_RING)
                        .encryptionRequired(true)
                        .surrogate(SurrogateSpec.placeholder("Comment stored in jvault - {{link}}"))
                        .linkPlacements(Set.of(LinkPlacement.COMMENT))
                        .build(),
                PlacementPolicy.builder()
                        .id("sec-attachments")
                        .selector(new PolicySelector(DEPLOYMENT, "SEC", null, PartType.ATTACHMENT, null))
                        .placement(Placement.EXTERNAL)
                        .classification(Classification.RESTRICTED)
                        .storageRoute("obj-dc1-restricted").keyRing(KEY_RING)
                        .encryptionRequired(true)
                        .surrogate(SurrogateSpec.placeholder(
                                "Evidence file ({{sizeHuman}}, {{mediaType}}) - {{link}}"))
                        .linkPlacements(Set.of(LinkPlacement.REMOTE_LINK))
                        .build(),
                PlacementPolicy.builder()
                        .id("eng-default")
                        .selector(new PolicySelector(DEPLOYMENT, "ENG", null, null, null))
                        .placement(Placement.JIRA)
                        .classification(Classification.INTERNAL)
                        .build()));
    }

    private List<JiraSafePayload> dispatchAll() {
        var sent = new ArrayList<JiraSafePayload>();
        new OutboxDispatcher(outbox, assembler, EgressGuard.withDefaults(),
                payload -> {
                    sent.add(payload);
                    return JiraWriteGateway.JiraWriteResult.succeeded("10001", "SEC-4471");
                },
                PerIssueRateLimiter.jiraCloudDefaults(), BackoffPolicy.atlassianDefault(),
                noOpTicketStates(), clock, new Random(7)).runOnce(20);
        return sent;
    }

    private static String everythingSent(List<JiraSafePayload> sent) {
        return sent.stream()
                .flatMap(p -> p.textFields().values().stream())
                .reduce("", (a, b) -> a + System.lineSeparator() + b);
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

    /** A stream of a known size that never materialises the whole payload. */
    private static InputStream repeating(int size) {
        return new InputStream() {
            private int remaining = size;

            @Override
            public int read() {
                return remaining-- > 0 ? 'A' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int n = Math.min(len, remaining);
                Arrays.fill(b, off, off + n, (byte) 'A');
                remaining -= n;
                return n;
            }
        };
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
            public void ticketBecameActive(String t, String id, String key, Instant when) {
            }

            @Override
            public void ticketBecameAmbiguous(String t, AmbiguityContext c) {
            }

            @Override
            public void ticketFailed(String t, String code, Instant when) {
            }
        };
    }
}
