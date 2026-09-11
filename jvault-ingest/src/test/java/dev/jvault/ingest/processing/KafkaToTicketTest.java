package dev.jvault.ingest.processing;

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
import dev.jvault.ingest.mapping.EventMapping;
import dev.jvault.ingest.support.Mappings;
import dev.jvault.ingest.support.InMemoryIngestion;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An event on a topic becoming a ticket, with every real component in the path except Kafka
 * itself and the Jira transport.
 *
 * <p>The Kafka client is a thin adapter over {@link MessageProcessor}; everything that decides
 * correctness — deduplication, validation, mapping, dead-lettering — lives here and is testable
 * without a broker. That is deliberate: a test suite that needs a running Kafka to check
 * duplicate prevention is a suite that gets skipped.
 */
class KafkaToTicketTest {

    private static final String CANARY =
            "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private static final String TOPIC = "security.alerts.v1";
    private static final String KEY_RING = "sec-restricted";

    private static final String VALID_EVENT = """
            {
              "alert": {
                "id": "alert-8f21c",
                "revision": 1,
                "severity": "HIGH",
                "title": "Unexpected outbound connection",
                "narrative": "%s",
                "tags": [],
                "evidence": [],
                "resolved": null
              },
              "reporter": {"email": "soc@example.com"}
            }
            """.formatted(CANARY);

    @TempDir
    Path storageRoot;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T09:41:12Z"), ZoneOffset.UTC);
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final InMemoryContentMetadataRepository metadata = new InMemoryContentMetadataRepository();
    private final InMemoryIngestion.State state = new InMemoryIngestion.State();
    private final InMemoryIngestion.Quarantine quarantine = new InMemoryIngestion.Quarantine();
    private final InMemoryIngestion.DeadLetters deadLetters = new InMemoryIngestion.DeadLetters();

    private MessageProcessor processor;
    private ContentService contentService;

    @BeforeEach
    void setUp() {
        var kms = LocalKeyManagementService.withKeyRings(KEY_RING);
        var store = new FilesystemContentStore("fs-local", storageRoot);
        var ids = sequentialIds();

        contentService = new ContentService(new ContentCipher(kms, 4096), store, metadata,
                ids, clock, "acme", storageRoot.resolve("spool"));
        var creation = new TicketCreationService(policies(), contentService, tickets, outbox,
                new LinkFactory("https://jvault.example.com"), ids, clock);

        processor = new MessageProcessor(Mappings.incident(), creation, state,
                quarantine, deadLetters, clock);
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        @DisplayName("an event becomes a ticket with its sensitive narrative externalised")
        void eventBecomesTicket() throws Exception {
            MessageProcessor.Outcome outcome = processor.process(message(0, VALID_EVENT));

            assertThat(outcome.state()).isEqualTo(MessageState.COMPLETE);
            assertThat(outcome.ticketRef()).isNotNull();

            var ticket = tickets.find(outcome.ticketRef()).orElseThrow();
            assertThat(ticket.jiraFields().get("summary"))
                    .isEqualTo("[HIGH] Unexpected outbound connection");
            assertThat(ticket.jiraFields().get("description"))
                    .doesNotContain("AWS_SECRET_ACCESS_KEY")
                    .contains("https://jvault.example.com/c/");

            assertThat(rawBytesOnDisk()).doesNotContain("AWS_SECRET_ACCESS_KEY");
        }

        @Test
        @DisplayName("the correlation id reaches the Jira effect that will need it")
        void correlationIdIsCarried() {
            processor.process(message(0, VALID_EVENT));

            assertThat(outbox.all()).anySatisfy(entry ->
                    assertThat(entry.payloadRef()).containsEntry("correlationId", "corr-1"));
        }

        @Test
        @DisplayName("the originating actor is recorded, and the identity stays the integration one")
        void actorAndIdentityAreSeparate() {
            var outcome = processor.process(message(0, VALID_EVENT));
            var ticket = tickets.find(outcome.ticketRef()).orElseThrow();

            // The event created the ticket, not a person — but who reported it is preserved.
            assertThat(ticket.origin().actorId()).isEqualTo("soc@example.com");
            assertThat(ticket.origin().identityRef()).startsWith("INTEGRATION:");
        }
    }

    @Nested
    @DisplayName("duplicate prevention")
    class Duplicates {

        @Test
        @DisplayName("redelivery of the same offset does no work at all")
        void redeliveryIsANoOp() {
            processor.process(message(0, VALID_EVENT));
            int outboxAfterFirst = outbox.all().size();

            MessageProcessor.Outcome second = processor.process(message(0, VALID_EVENT));

            assertThat(second.state()).isEqualTo(MessageState.COMPLETE);
            assertThat(outbox.all()).hasSize(outboxAfterFirst);
            assertThat(metadata.partsOf(second.ticketRef())).hasSize(1);
        }

        @Test
        @DisplayName("the same business event on a new offset is recognised as a duplicate")
        void republicationIsADuplicate() {
            MessageProcessor.Outcome first = processor.process(message(0, VALID_EVENT));

            // A topic replay, or a producer retry: same event, different offset.
            MessageProcessor.Outcome second = processor.process(message(1, VALID_EVENT));

            assertThat(second.state()).isEqualTo(MessageState.DUPLICATE);
            assertThat(second.ticketRef()).isEqualTo(first.ticketRef());
            assertThat(tickets.find(first.ticketRef())).isPresent();
            assertThat(outbox.all()).hasSize(3);
        }

        @Test
        @DisplayName("a genuinely new revision is a new ticket")
        void newRevisionIsNewTicket() {
            processor.process(message(0, VALID_EVENT));

            MessageProcessor.Outcome second = processor.process(
                    message(1, VALID_EVENT.replace("\"revision\": 1", "\"revision\": 2")));

            assertThat(second.state()).isEqualTo(MessageState.COMPLETE);
            assertThat(outbox.all()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("dead-lettering carries no payload")
    class DeadLettering {

        @Test
        @DisplayName("a malformed event is dead-lettered before any Jira call or content write")
        void malformedJson() {
            MessageProcessor.Outcome outcome = processor.process(message(0, "{not json"));

            assertThat(outcome.state()).isEqualTo(MessageState.DEAD_LETTERED);
            assertThat(outbox.all()).isEmpty();
            assertThat(metadata.partsOf("any")).isEmpty();
            assertThat(deadLetters.only().failure().stage()).isEqualTo("PARSE");
        }

        @Test
        @DisplayName("the dead-letter record contains none of the event, only coordinates and codes")
        void deadLetterRecordIsClean() {
            String invalid = VALID_EVENT.replace("\"title\": \"Unexpected outbound connection\",", "");

            processor.process(message(0, invalid));

            DeadLetterRecord record = deadLetters.only();
            // The message that failed still carried the credential; republishing it to a topic
            // with different retention and different ACLs is exactly the leak this prevents.
            assertThat(record.toString()).doesNotContain("AWS_SECRET_ACCESS_KEY");
            assertThat(String.valueOf(record.fieldProblems())).doesNotContain("AWS_SECRET");
            assertThat(record.source().topic()).isEqualTo(TOPIC);
            assertThat(record.source().offset()).isZero();
        }

        @Test
        @DisplayName("the original is preserved in the encrypted quarantine, and referenced by id")
        void originalGoesToQuarantine() {
            processor.process(message(0, "{not json"));

            DeadLetterRecord record = deadLetters.only();
            assertThat(record.quarantineRef()).isNotNull();
            assertThat(quarantine.holds(record.quarantineRef())).isTrue();

            // Replay reads from here, not from the dead-letter topic, and the read is audited.
            assertThat(new String(quarantine.read(record.quarantineRef()), StandardCharsets.UTF_8))
                    .isEqualTo("{not json");
        }

        @Test
        @DisplayName("field problems name the field and the violation, never the value")
        void fieldProblemsAreCodesOnly() {
            String invalid = VALID_EVENT.replace("\"severity\": \"HIGH\",", "");

            processor.process(message(0, invalid));

            assertThat(deadLetters.only().fieldProblems())
                    .isNotEmpty()
                    .allSatisfy(problem -> {
                        assertThat(problem.code()).matches("[A-Z_]+");
                        assertThat(problem.field()).doesNotContain("wJalrXUtnFEMI");
                    });
        }

        @Test
        @DisplayName("if quarantining fails the record says so rather than falling back to the payload")
        void quarantineFailureIsRecordedNotWorkedAround() {
            quarantine.failNext(true);

            processor.process(message(0, "{not json"));

            DeadLetterRecord record = deadLetters.only();
            // Losing the evidence is bad; publishing it to avoid losing it would be worse.
            assertThat(record.quarantineRef()).isNull();
            assertThat(record.failure().code()).endsWith("_UNQUARANTINED");
            assertThat(record.toString()).doesNotContain("not json");
        }

        @Test
        @DisplayName("a dead-lettered offset is terminal and is not reprocessed")
        void deadLetteredIsTerminal() {
            processor.process(message(0, "{not json"));
            processor.process(message(0, "{not json"));

            assertThat(deadLetters.published()).hasSize(1);
            assertThat(quarantine.size()).isEqualTo(1);
        }
    }

    // --- fixtures ----------------------------------------------------------------

    private MessageProcessor.ConsumedMessage message(long offset, String payload) {
        return new MessageProcessor.ConsumedMessage(TOPIC, 0, offset, "alert-8f21c", payload,
                "corr-" + (offset + 1));
    }

    private PolicySet policies() {
        return PolicySet.of(List.of(
                PlacementPolicy.builder()
                        .id("sec-incident-description")
                        .selector(new PolicySelector("jira-cloud-prod", "SEC", "10004",
                                PartType.DESCRIPTION, null))
                        .placement(Placement.EXTERNAL)
                        .classification(Classification.RESTRICTED)
                        .storageRoute("obj-dc1-restricted").keyRing(KEY_RING)
                        .encryptionRequired(true)
                        .surrogate(SurrogateSpec.placeholder(
                                "Incident details are stored in jvault. Open: {{link}}"))
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
}
