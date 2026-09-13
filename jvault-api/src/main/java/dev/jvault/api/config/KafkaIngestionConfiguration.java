package dev.jvault.api.config;

import dev.jvault.content.TicketCreationService;
import dev.jvault.crypto.text.SensitiveTextCipher;
import dev.jvault.ingest.kafka.KafkaDeadLetterPublisher;
import dev.jvault.ingest.kafka.KafkaMessageListener;
import dev.jvault.ingest.mapping.EventMapping;
import dev.jvault.ingest.mapping.EventMappingLoader;
import dev.jvault.ingest.mapping.MappingConfigurationException;
import dev.jvault.ingest.processing.IngestionStateRepository;
import dev.jvault.ingest.processing.MessageProcessor;
import dev.jvault.ingest.processing.QuarantineStore;
import dev.jvault.persistence.JdbcIngestionStateRepository;
import dev.jvault.persistence.JdbcQuarantineStore;
import dev.jvault.persistence.SchemaMigrator;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka ingestion, when a deployment asks for it.
 *
 * <p>Off unless {@code jvault.kafka.enabled} is true. A consumer that starts because nobody
 * turned it off is a consumer creating tickets nobody expected, in a Jira somebody else owns.
 *
 * <p>One listener container per mapping, because a mapping names its own topic, consumer group
 * and concurrency — the HR intake that must stay in order and the build-failure firehose are not
 * the same problem and should not share a setting.
 */
@Configuration
@ConditionalOnProperty(prefix = "jvault.kafka", name = "enabled", havingValue = "true")
public class KafkaIngestionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestionConfiguration.class);

    @Bean
    public IngestionStateRepository ingestionStateRepository(DataSource dataSource,
                                                             SchemaMigrator migrated) {
        return new JdbcIngestionStateRepository(dataSource);
    }

    @Bean
    public QuarantineStore quarantineStore(DataSource dataSource,
                                           SensitiveTextCipher cipher,
                                           JvaultProperties properties,
                                           Clock clock,
                                           SchemaMigrator migrated) {
        return new JdbcQuarantineStore(dataSource, cipher,
                properties.kafka().quarantineKeyRing(), clock);
    }

    @Bean
    public KafkaTemplate<String, byte[]> jvaultKafkaTemplate(JvaultProperties properties) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(properties),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                // A dead letter that was not durably written is a message that disappeared.
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)));
    }

    /**
     * The containers, started and stopped with the application.
     *
     * <p>Grouped into one lifecycle bean so that "is ingestion running" is a single thing rather
     * than a count of beans nobody enumerates.
     */
    @Bean
    public IngestionContainers ingestionContainers(JvaultProperties properties,
                                                   TicketCreationService creation,
                                                   IngestionStateRepository state,
                                                   QuarantineStore quarantine,
                                                   KafkaTemplate<String, byte[]> kafka,
                                                   Clock clock) {
        Path mappingsFile = mappingsFile(properties);
        List<EventMapping> mappings = read(mappingsFile, EventMappingLoader::load);
        Map<String, EventMappingLoader.Consumer> consumers =
                read(mappingsFile, EventMappingLoader::consumersIn);

        var containers = new ArrayList<ConcurrentMessageListenerContainer<String, byte[]>>();
        for (EventMapping mapping : mappings) {
            EventMappingLoader.Consumer settings = consumers.get(mapping.id());
            String deadLetterTopic = mapping.topic() + properties.kafka().deadLetterSuffix();

            var processor = new MessageProcessor(mapping, creation, state, quarantine,
                    new KafkaDeadLetterPublisher(kafka, deadLetterTopic), clock);

            containers.add(containerFor(properties, mapping, settings,
                    new KafkaMessageListener(processor)));

            log.info("Ingesting {} as group {} with concurrency {}; dead letters to {}",
                    mapping.topic(), settings.groupId(), settings.concurrency(), deadLetterTopic);
        }
        return new IngestionContainers(containers);
    }

    private ConcurrentMessageListenerContainer<String, byte[]> containerFor(
            JvaultProperties properties,
            EventMapping mapping,
            EventMappingLoader.Consumer settings,
            KafkaMessageListener listener) {

        var consumerProperties = new HashMap<String, Object>();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(properties));
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, settings.groupId());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Offsets are committed by the listener after the work, never by a timer during it.
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);

        var containerProperties = new ContainerProperties(mapping.topic());
        containerProperties.setMessageListener(listener);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        containerProperties.setGroupId(settings.groupId());

        var container = new ConcurrentMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, byte[]>(consumerProperties),
                containerProperties);
        container.setConcurrency(settings.concurrency());
        container.setBeanName("ingest-" + mapping.id());
        container.setAutoStartup(false);
        return container;
    }

    private static String bootstrap(JvaultProperties properties) {
        String servers = properties.kafka().bootstrapServers();
        if (servers == null || servers.isBlank()) {
            throw new IllegalStateException(
                    "jvault.kafka.enabled is true but jvault.kafka.bootstrap-servers is not set");
        }
        return servers;
    }

    private static Path mappingsFile(JvaultProperties properties) {
        String configured = properties.kafka().mappingsFile();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "jvault.kafka.enabled is true but jvault.kafka.mappings-file is not set. "
                            + "There is no default for what a message means.");
        }
        Path file = Path.of(configured);
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("the Kafka mappings file " + file + " is not readable");
        }
        return file;
    }

    private static <T> T read(Path file, java.util.function.Function<InputStream, T> reader) {
        try (InputStream in = Files.newInputStream(file)) {
            return reader.apply(in);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        } catch (MappingConfigurationException e) {
            // Refusing to start is the point: a mapping the loader only partly understands is a
            // mapping that would put content somewhere nobody chose.
            throw new IllegalStateException("the Kafka mappings in " + file
                    + " were rejected: " + e.getMessage(), e);
        }
    }

    /** Starts the containers after the rest of the context is up, and stops them first. */
    public static final class IngestionContainers implements SmartLifecycle {

        private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers;
        private volatile boolean running;

        IngestionContainers(List<ConcurrentMessageListenerContainer<String, byte[]>> containers) {
            this.containers = List.copyOf(containers);
        }

        @Override
        public void start() {
            containers.forEach(ConcurrentMessageListenerContainer::start);
            running = true;
        }

        @Override
        public void stop() {
            // Stopped before the database and the storage backends go away: a listener still
            // consuming while its dependencies close is a message failed for the wrong reason.
            containers.forEach(ConcurrentMessageListenerContainer::stop);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return Integer.MAX_VALUE - 100;
        }
    }
}
