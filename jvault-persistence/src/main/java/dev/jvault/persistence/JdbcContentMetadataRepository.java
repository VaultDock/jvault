package dev.jvault.persistence;

import dev.jvault.content.ContentMetadataRepository;
import dev.jvault.content.ContentRecord;
import dev.jvault.crypto.text.SensitiveTextCipher;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PartType;
import dev.jvault.storage.spi.ObjectKey;
import dev.jvault.storage.spi.StoredObjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Content metadata on Spring JDBC: what was stored, where, and under which key.
 *
 * <p>Not the content. Every byte of that lives encrypted in a storage backend; these rows hold
 * what is needed to find it, prove it has not changed, and unwrap the key that opens it.
 *
 * <p>The part and its versions are separate tables because the part reference is the permanent
 * public identifier — a link points at it, and versions, storage objects and even backends change
 * beneath it without the link changing (FR-LNK-1).
 *
 * <p>The display name is encrypted before it reaches a column. An attachment called
 * {@code 2026-Q3-layoffs-final.xlsx} tells you most of what the file contains, so storing it in
 * the clear would undo much of what encrypting the bytes achieved (docs/04-data-model.md 4.2).
 */
public final class JdbcContentMetadataRepository implements ContentMetadataRepository {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcContentMetadataRepository.class);

    /** The insert list. Kept beside the placeholder count so the two are edited together. */
    private static final String VERSION_COLUMNS = """
            version_id, content_ref, version_no, key_ring, kek_id, wrapped_dek,
            plaintext_sha256, ciphertext_sha256, size_bytes, media_type, display_name_enc,
            display_name_kek, display_name_dek, display_name_label, storage_route, object_key,
            backend_version_id, created_at""";

    /**
     * A part joined to one of its versions. Written out rather than assembled from the insert
     * list: qualifying a generated list is the kind of string surgery that produces SQL which
     * looks right in the source and is a syntax error on the wire.
     */
    private static final String SELECT_JOINED = """
            SELECT v.version_id, v.content_ref, v.version_no, v.key_ring, v.kek_id,
                   v.wrapped_dek, v.plaintext_sha256, v.ciphertext_sha256, v.size_bytes,
                   v.media_type, v.display_name_enc, v.display_name_kek, v.display_name_dek,
                   v.display_name_label, v.storage_route, v.object_key, v.created_at,
                   p.ticket_ref, p.part_type, p.field_key, p.classification, p.jira_surrogate
            FROM content_version v
            JOIN content_part p ON p.content_ref = v.content_ref
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SensitiveTextCipher names;

    public JdbcContentMetadataRepository(DataSource dataSource, SensitiveTextCipher names) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.names = Objects.requireNonNull(names, "names");
    }

    @Override
    public void record(ContentRecord content) {
        transactions.executeWithoutResult(status -> {
            upsertPart(content);
            insertVersion(content);
        });
    }

    private void upsertPart(ContentRecord content) {
        int updated = jdbc.update("""
                        UPDATE content_part SET current_version_no = ?, jira_surrogate = ?
                        WHERE content_ref = ?""",
                content.versionNo(), content.jiraSurrogate(), content.contentRef());

        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO content_part (
                                content_ref, ticket_ref, part_type, field_key, classification,
                                jira_surrogate, current_version_no, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    content.contentRef(), content.ticketRef(), content.partType().name(),
                    content.fieldKey(), content.classification().name(), content.jiraSurrogate(),
                    content.versionNo(), Timestamp.from(content.createdAt()));
        }
    }

    private void insertVersion(ContentRecord content) {
        SensitiveTextCipher.Sealed sealedName = sealName(content);

        jdbc.update("INSERT INTO content_version (" + VERSION_COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                content.versionId(),
                content.contentRef(),
                content.versionNo(),
                content.keyRing(),
                content.kekId(),
                content.wrappedDataKey(),
                content.plaintextSha256(),
                content.ciphertextSha256(),
                content.sizeBytes(),
                content.mediaType(),
                sealedName == null ? null : sealedName.ciphertext(),
                sealedName == null ? null : sealedName.kekId(),
                sealedName == null ? null : sealedName.wrappedKey(),
                content.displayName() == null ? null : content.displayName().label(),
                content.storedObject() == null ? null : content.storedObject().backendName(),
                content.storedObject() == null ? null : content.storedObject().key().asPath(),
                null,
                Timestamp.from(content.createdAt()));
    }

    private SensitiveTextCipher.Sealed sealName(ContentRecord content) {
        if (content.displayName() == null) {
            return null;
        }
        // Bound to the version, so a filename cannot be quietly moved onto a different object by
        // anyone with write access to the table but not to the key.
        return names.seal(content.keyRing(), content.displayName(), content.versionId());
    }

    @Override
    public Optional<ContentRecord> findCurrent(String contentRef) {
        return one(SELECT_JOINED
                + "WHERE v.content_ref = ? AND v.version_no = p.current_version_no", contentRef);
    }

    @Override
    public Optional<ContentRecord> findVersion(String contentRef, int versionNo) {
        return one(SELECT_JOINED + "WHERE v.content_ref = ? AND v.version_no = ?",
                contentRef, versionNo);
    }

    @Override
    public List<ContentRecord> versionsOf(String contentRef) {
        return jdbc.query(SELECT_JOINED + "WHERE v.content_ref = ? ORDER BY v.version_no",
                this::mapContent, contentRef);
    }

    @Override
    public List<ContentRecord> partsOf(String ticketRef) {
        return jdbc.query(SELECT_JOINED
                        + "WHERE p.ticket_ref = ? AND v.version_no = p.current_version_no"
                        + " ORDER BY p.created_at, p.content_ref",
                this::mapContent, ticketRef);
    }

    private Optional<ContentRecord> one(String sql, Object... args) {
        List<ContentRecord> found = jdbc.query(sql, this::mapContent, args);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private ContentRecord mapContent(ResultSet rs, int rowNum) throws SQLException {
        String contentRef = rs.getString("content_ref").trim();
        String versionId = rs.getString("version_id").trim();

        return new ContentRecord(
                contentRef,
                versionId,
                rs.getInt("version_no"),
                rs.getString("ticket_ref").trim(),
                PartType.valueOf(rs.getString("part_type")),
                rs.getString("field_key"),
                Classification.valueOf(rs.getString("classification")),
                rs.getString("key_ring"),
                rs.getString("kek_id"),
                rs.getBytes("wrapped_dek"),
                rs.getBytes("plaintext_sha256"),
                rs.getBytes("ciphertext_sha256"),
                rs.getLong("size_bytes"),
                rs.getString("media_type"),
                displayName(rs, versionId),
                rs.getString("jira_surrogate"),
                storedObject(rs),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Rebuilt from the key that was written, not from the one this instance would write now.
     * A tenant renamed in configuration must not silently re-point every existing object at a
     * path that holds nothing.
     */
    private static StoredObjectRef storedObject(ResultSet rs) throws SQLException {
        String route = rs.getString("storage_route");
        String path = rs.getString("object_key");
        if (route == null || path == null) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length != 3) {
            throw new PersistenceException(
                    "stored object key '" + path + "' is not tenant/contentRef/versionId", null);
        }
        return new StoredObjectRef(route, new ObjectKey(parts[0], parts[1], parts[2]));
    }

    /**
     * The filename, if it can be read.
     *
     * <p>A failure here is deliberately not fatal to the row. Everything else in it — the
     * hashes, the storage location, the classification, the wrapped content key — is still true
     * and still needed, including by the code that would have to diagnose why a key stopped
     * working. Letting an unreadable name take the whole record down turns a lost filename into
     * a ticket that cannot be dispatched, which is what it did.
     */
    private SensitiveValue displayName(ResultSet rs, String versionId) throws SQLException {
        byte[] ciphertext = rs.getBytes("display_name_enc");
        if (ciphertext == null) {
            return null;
        }
        var sealed = new SensitiveTextCipher.Sealed(
                rs.getString("display_name_kek"), rs.getBytes("display_name_dek"), ciphertext);
        String label = rs.getString("display_name_label");
        try {
            return names.open(rs.getString("key_ring"), sealed, versionId,
                    label == null ? "display name" : label);
        } catch (RuntimeException e) {
            // Identifiers only: the exception knows the key ring and the version, and neither is
            // content. Logged at error because a key that cannot open its own data is a fault.
            log.error("Could not decrypt the display name of version {} under ring {}",
                    versionId, rs.getString("key_ring"), e);
            return null;
        }
    }
}
