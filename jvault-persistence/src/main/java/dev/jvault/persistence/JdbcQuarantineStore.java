package dev.jvault.persistence;

import dev.jvault.crypto.text.SensitiveTextCipher;
import dev.jvault.ingest.processing.QuarantineStore;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The payloads of messages that could not be processed.
 *
 * <p>Kept so that somebody can see what actually arrived, and encrypted because a payload that
 * failed validation is still whatever the producer put in it. A message rejected for a malformed
 * date may well carry a salary, a diagnosis or a name in the fields that parsed fine — a
 * quarantine table in the clear would be the one place in the system where content sits
 * unprotected, and it would be full of exactly the messages nobody reviewed.
 *
 * <p>Bounded, because a poison message can be any size a producer felt like sending and a
 * quarantine that accepts anything is a way to fill the database from outside.
 */
public final class JdbcQuarantineStore implements QuarantineStore {

    /** Comfortably above Kafka's usual 1 MiB message limit, well below anything alarming. */
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final SensitiveTextCipher cipher;
    private final String keyRing;
    private final Clock clock;

    public JdbcQuarantineStore(DataSource dataSource,
                               SensitiveTextCipher cipher,
                               String keyRing,
                               Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String quarantine(String correlationId, byte[] payload) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");

        byte[] kept = payload.length > MAX_PAYLOAD_BYTES
                // Truncated rather than refused: a fragment of a poison message still tells
                // somebody what went wrong, and refusing would lose the evidence entirely.
                ? java.util.Arrays.copyOf(payload, MAX_PAYLOAD_BYTES)
                : payload;

        String ref = UUID.randomUUID().toString().replace("-", "");
        SensitiveTextCipher.Sealed sealed = cipher.seal(keyRing, kept, ref);

        jdbc.update("""
                        INSERT INTO quarantined_payload (
                            quarantine_ref, correlation_id, key_ring, kek_id, wrapped_dek,
                            payload_enc, size_bytes, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                ref, correlationId, keyRing, sealed.kekId(), sealed.wrappedKey(),
                sealed.ciphertext(), payload.length, Timestamp.from(clock.instant()));

        return ref;
    }

    @Override
    public byte[] read(String quarantineRef) {
        List<SensitiveTextCipher.Sealed> found = jdbc.query(
                "SELECT kek_id, wrapped_dek, payload_enc FROM quarantined_payload"
                        + " WHERE quarantine_ref = ?",
                (rs, rowNum) -> new SensitiveTextCipher.Sealed(
                        rs.getString("kek_id"), rs.getBytes("wrapped_dek"),
                        rs.getBytes("payload_enc")),
                quarantineRef);

        if (found.isEmpty()) {
            throw new PersistenceException("no quarantined payload " + quarantineRef, null);
        }
        // The reference is the binding, so a row moved to a different ref does not open.
        return cipher.openBytes(keyRing, found.get(0), quarantineRef);
    }
}
