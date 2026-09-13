package dev.jvault.api.jira;

import dev.jvault.content.JiraFieldEncodings;
import dev.jvault.jira.egress.JiraFieldEncoding;
import dev.jvault.jira.gateway.JiraMetadataGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Field encodings from Jira's own create metadata.
 *
 * <p>The field key says nothing about a custom field's shape. {@code customfield_10021} is a
 * checkbox group on one deployment and a date on another, and only Jira knows which — so this
 * asks, rather than the payload assuming and the write coming back rejected.
 *
 * <p>Cached, because one dispatch pass assembles many payloads for the same project and issue
 * type and createmeta is not a cheap call. Entries expire rather than being invalidated: an
 * administrator changing a field's type is rare, waiting a few minutes for it is tolerable, and
 * a cache that has to be told when to forget is one that eventually is not told.
 */
public final class CreateMetaFieldEncodings implements JiraFieldEncodings {

    private static final Logger log = LoggerFactory.getLogger(CreateMetaFieldEncodings.class);

    private final JiraMetadataGateway metadata;
    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public CreateMetaFieldEncodings(JiraMetadataGateway metadata, Clock clock, Duration ttl) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public JiraFieldEncoding encodingFor(String projectKey, String issueTypeId, String fieldKey) {
        JiraFieldEncoding fromKey = JiraFieldEncoding.forField(null, null, null, fieldKey);
        if (projectKey == null || issueTypeId == null || fieldKey == null) {
            return fromKey;
        }

        // A field createmeta does not mention is one this project cannot set. The write is going
        // to fail either way; failing the way the key implies at least produces an error that
        // names the field rather than a shape mismatch that names nothing.
        return describe(projectKey, issueTypeId).getOrDefault(fieldKey, fromKey);
    }

    private Map<String, JiraFieldEncoding> describe(String projectKey, String issueTypeId) {
        String key = projectKey + "/" + issueTypeId;
        Instant now = clock.instant();

        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.encodings();
        }

        try {
            Map<String, JiraFieldEncoding> fresh = metadata.fields(projectKey, issueTypeId)
                    .stream()
                    .collect(Collectors.toMap(JiraMetadataGateway.FieldMeta::key,
                            field -> JiraFieldEncoding.forField(field.schemaType(),
                                    field.schemaItems(), field.customType(), field.key()),
                            (first, second) -> first));
            cache.put(key, new Entry(fresh, now.plus(ttl)));
            return fresh;
        } catch (RuntimeException e) {
            // Jira being unreachable is the outbox's problem rather than this one's: the pass
            // this belongs to is about to fail at the write and retry. Until then, a stale
            // answer beats none, and none beats refusing to assemble anything at all.
            log.warn("Could not read create metadata for {}; falling back to what the field "
                    + "keys imply.", key, e);
            return cached == null ? Map.of() : cached.encodings();
        }
    }

    private record Entry(Map<String, JiraFieldEncoding> encodings, Instant expiresAt) {
    }
}
