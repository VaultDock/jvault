package dev.jvault.ingest.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.jvault.ingest.processing.MessageProcessor;
import dev.jvault.ingest.support.InMemoryIngestion;
import dev.jvault.ingest.support.Mappings;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion against a real broker.
 *
 * <p>{@code KafkaToTicketTest} already proves what a message becomes. What only a broker can
 * show is what the in-memory test cannot fake: whether a republished event produces a second
 * ticket, whether a poison message blocks everything behind it, and whether the dead-letter
 * topic ends up holding a copy of the payload it exists to keep out.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaIngestionIT {

    private static final String KEY_RING = "sec-restricted";
    private static final String CANARY = "PATIENT-RECORD-CANARY-8831";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @TempDir
    Path storageRoot;

    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final InMemoryIngestion.State state = new InMemoryIngestion.State();
    private final InMemoryIngestion.Quarantine quarantine = new InMemoryIngestion.Quarantine();

    private ConcurrentMessageListenerContainer<String, byte[]> listener;
    private KafkaTemplate<String, byte[]> producer;

    // A topic per test. One shared topic plus a fresh consumer group per test means every test
    // replays every earlier test's messages, which is how three of these first "failed".
    private String topic;
    private String dlq;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        topic = "alerts." + suffix;
        dlq = "alerts." + suffix + ".dlq";
        createTopics(topic, dlq);

        var kms = LocalKeyManagementService.withKeyRings(KEY_RING);
        var content = new ContentService(new ContentCipher(kms, 4096),
                new FilesystemContentStore("fs-local", storageRoot),
                new InMemoryContentMetadataRepository(), ContentService.IdGenerator.random(),
                Clock.systemUTC(), "acme", storageRoot.resolve("spool"));

        var creation = new TicketCreationService(policies(), content, tickets,
                new InMemoryOutboxRepository(), new LinkFactory("http://localhost:8080"),
                ContentService.IdGenerator.random(), Clock.systemUTC());

        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));

        var processor = new MessageProcessor(Mappings.incident(), creation, state, quarantine,
                new KafkaDeadLetterPublisher(producer, dlq), Clock.systemUTC());

        listener = listenerFor(new KafkaMessageListener(processor));
        listener.start();
        waitFor("the listener to join the group", listener::isRunning);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
        }
    }

    @Test
    @DisplayName("a message on the topic becomes a ticket, and its content does not reach Jira")
    void messageBecomesTicket() {
        send("alert-1", event("alert-1", 1));

        waitFor("a ticket", () -> tickets.count() == 1);

        var ticket = tickets.all().get(0);
        assertThat(ticket.jiraFields().get("summary")).startsWith("[HIGH]");
        // Policy placed the narrative externally, so Jira is told a surrogate.
        assertThat(ticket.jiraFields().get("description")).doesNotContain(CANARY);
        assertThat(ticket.externalContentRefs()).isNotEmpty();
    }

    @Test
    @DisplayName("the same event republished under a new offset does not create a second ticket")
    void republishedEventIsDeduplicated() {
        send("alert-2", event("alert-2", 1));
        waitFor("the first ticket", () -> tickets.count() == 1);

        // A producer retry, a topic replay, a migration: the same event at a new offset. Kafka's
        // own offsets say nothing about this, which is what the dedupe key is for.
        send("alert-2", event("alert-2", 1));
        send("alert-9", event("alert-9", 1));

        // The second alert arriving is what proves the first was considered and rejected, rather
        // than the assertion simply running before it was processed.
        waitFor("the second distinct ticket", () -> tickets.count() == 2);
        assertThat(tickets.all()).extracting(t -> t.jiraFields().get("customfield_10010"))
                .containsExactlyInAnyOrder("alert-2", "alert-9");
    }

    @Test
    @DisplayName("a message that cannot be mapped is dead-lettered without its payload")
    void poisonMessagesAreDeadLettered() throws Exception {
        // No alert id, which the mapping requires, so this fails before anything is created.
        send("broken", """
                {"alert": {"severity": "HIGH", "title": "No id", "narrative": "%s"},
                 "reporter": {"email": "soc@example.com"}}
                """.formatted(CANARY));

        List<String> letters = drain(dlq, 1);
        assertThat(letters).hasSize(1);

        var record = JSON.readTree(letters.get(0));
        assertThat(record.path("failure").path("code").asText()).isNotBlank();
        String quarantineRef = record.path("quarantineRef").asText();
        assertThat(quarantineRef).isNotBlank();

        // A dead-letter topic has different consumers, different retention and usually different
        // access from the topic it mirrors. A copy of the payload there is content sitting
        // somewhere nobody decided it should be.
        assertThat(letters.get(0)).doesNotContain(CANARY).doesNotContain("No id");

        // And it is still recoverable by whoever has to work out what went wrong.
        assertThat(new String(quarantine.read(quarantineRef), StandardCharsets.UTF_8))
                .contains(CANARY);
        assertThat(tickets.count()).isZero();
    }

    @Test
    @DisplayName("a dead-lettered message is acknowledged, so the partition keeps moving")
    void deadLetteredMessagesDoNotBlockThePartition() {
        send("broken", "{\"not\": \"an alert\"}");
        send("alert-3", event("alert-3", 1));

        // Redelivering a poison message forever is how a partition stops. The good message
        // behind it has to arrive.
        waitFor("the message behind the poison one", () -> tickets.count() == 1);
        assertThat(tickets.all().get(0).jiraFields().get("customfield_10010")).isEqualTo("alert-3");
    }

    // --- helpers -----------------------------------------------------------------

    private void send(String key, String payload) {
        producer.send(topic, key, payload.getBytes(StandardCharsets.UTF_8));
        producer.flush();
    }

    /**
     * Created up front rather than left to auto-creation: a listener that subscribes to a topic
     * which does not exist yet is a listener that may or may not start, depending on how quickly
     * the broker notices.
     */
    private static void createTopics(String... names) {
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var topics = new ArrayList<org.apache.kafka.clients.admin.NewTopic>();
            for (String name : names) {
                topics.add(new org.apache.kafka.clients.admin.NewTopic(name, 1, (short) 1));
            }
            admin.createTopics(topics).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted creating topics", e);
        } catch (Exception e) {
            throw new IllegalStateException("could not create topics", e);
        }
    }

    /** Polling rather than a library: one helper is cheaper than another dependency. */
    private static void waitFor(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static String event(String id, int revision) {
        return """
                {
                  "alert": {
                    "id": "%s",
                    "revision": %d,
                    "severity": "HIGH",
                    "title": "Unexpected outbound connection",
                    "narrative": "%s",
                    "tags": [],
                    "evidence": [],
                    "resolved": null
                  },
                  "reporter": {"email": "soc@example.com"}
                }
                """.formatted(id, revision, CANARY);
    }

    private ConcurrentMessageListenerContainer<String, byte[]> listenerFor(
            KafkaMessageListener messageListener) {
        var consumerProperties = new HashMap<String, Object>();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "jvault-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);

        var containerProperties = new ContainerProperties(topic);
        containerProperties.setMessageListener(messageListener);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return new ConcurrentMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, byte[]>(consumerProperties),
                containerProperties);
    }

    /** Reads a topic from the beginning until it holds what the test is waiting for. */
    private List<String> drain(String topic, int expected) {
        var properties = new HashMap<String, Object>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "drain-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        var found = new ArrayList<String>();
        try (var consumer = new DefaultKafkaConsumerFactory<String, byte[]>(properties)
                .createConsumer()) {
            consumer.subscribe(Set.of(topic));
            waitFor("a dead letter", () -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record ->
                        found.add(new String(record.value(), StandardCharsets.UTF_8)));
                return found.size() >= expected;
            });
        }
        return found;
    }

    private static PolicySet policies() {
        return PolicySet.of(List.of(PlacementPolicy.builder()
                .id("sec-narrative")
                .selector(new PolicySelector("jira-cloud-prod", "SEC", null,
                        PartType.DESCRIPTION, null))
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .storageRoute("fs-local")
                .keyRing(KEY_RING)
                .encryptionRequired(true)
                .surrogate(SurrogateSpec.placeholder("Held in jvault: {{link}}"))
                .linkPlacements(Set.of(LinkPlacement.REMOTE_LINK))
                .build()));
    }
}
