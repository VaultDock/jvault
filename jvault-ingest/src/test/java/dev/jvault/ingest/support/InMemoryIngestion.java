package dev.jvault.ingest.support;

import dev.jvault.ingest.processing.DeadLetterPublisher;
import dev.jvault.ingest.processing.DeadLetterRecord;
import dev.jvault.ingest.processing.IngestedMessage;
import dev.jvault.ingest.processing.IngestionStateRepository;
import dev.jvault.ingest.processing.QuarantineStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory ingestion ports, reproducing the constraints the real adapters get from the schema. */
public final class InMemoryIngestion {

    private InMemoryIngestion() {
    }

    public static final class State implements IngestionStateRepository {

        private final Map<String, IngestedMessage> byOffset = new LinkedHashMap<>();
        private final Map<String, String> dedupeKeys = new LinkedHashMap<>();
        private final Map<String, IngestedMessage> byDedupeKey = new LinkedHashMap<>();

        @Override
        public synchronized Optional<IngestedMessage> findByOffset(String topic, int partition,
                                                                   long offset) {
            return Optional.ofNullable(byOffset.get(offsetKey(topic, partition, offset)));
        }

        @Override
        public synchronized Optional<IngestedMessage> findByDedupeKey(String mappingId,
                                                                      String dedupeKey) {
            return Optional.ofNullable(byDedupeKey.get(mappingId + "|" + dedupeKey));
        }

        @Override
        public synchronized boolean reserveDedupeKey(String mappingId, String dedupeKey,
                                                     IngestedMessage message) {
            String key = mappingId + "|" + dedupeKey;
            // putIfAbsent, not containsKey-then-put: the real constraint is atomic and a fake
            // that races would let a test pass while production duplicated tickets.
            String existing = dedupeKeys.putIfAbsent(key, offsetKeyOf(message));
            if (existing != null && !existing.equals(offsetKeyOf(message))) {
                return false;
            }
            byDedupeKey.put(key, message);
            return true;
        }

        @Override
        public synchronized void save(IngestedMessage message) {
            byOffset.put(offsetKeyOf(message), message);
            if (message.dedupeKey() != null) {
                byDedupeKey.put(message.mappingId() + "|" + message.dedupeKey(), message);
            }
        }

        public synchronized List<IngestedMessage> all() {
            return List.copyOf(byOffset.values());
        }

        private static String offsetKeyOf(IngestedMessage m) {
            return offsetKey(m.topic(), m.partition(), m.offset());
        }

        private static String offsetKey(String topic, int partition, long offset) {
            return topic + "/" + partition + "/" + offset;
        }
    }

    public static final class Quarantine implements QuarantineStore {

        private final Map<String, byte[]> held = new LinkedHashMap<>();
        private final AtomicInteger counter = new AtomicInteger();
        private boolean failing;

        public void failNext(boolean failing) {
            this.failing = failing;
        }

        @Override
        public String quarantine(String correlationId, byte[] payload) {
            if (failing) {
                throw new IllegalStateException("quarantine unavailable");
            }
            String ref = "q-" + counter.incrementAndGet();
            held.put(ref, payload.clone());
            return ref;
        }

        @Override
        public byte[] read(String quarantineRef) {
            byte[] payload = held.get(quarantineRef);
            if (payload == null) {
                throw new IllegalArgumentException("no quarantined payload " + quarantineRef);
            }
            return payload.clone();
        }

        public boolean holds(String ref) {
            return held.containsKey(ref);
        }

        public int size() {
            return held.size();
        }
    }

    public static final class DeadLetters implements DeadLetterPublisher {

        private final List<DeadLetterRecord> published = new ArrayList<>();

        @Override
        public void publish(DeadLetterRecord record) {
            published.add(record);
        }

        public List<DeadLetterRecord> published() {
            return List.copyOf(published);
        }

        public DeadLetterRecord only() {
            if (published.size() != 1) {
                throw new IllegalStateException("expected one dead letter, got " + published.size());
            }
            return published.get(0);
        }
    }
}
