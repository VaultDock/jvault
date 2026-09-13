package dev.jvault.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a deployment has to decide.
 *
 * <p>Gathered into one place rather than scattered across {@code @Value} annotations, so that
 * "what does this installation need configured" is answerable by reading a file rather than by
 * grepping for injection points.
 */
@ConfigurationProperties(prefix = "jvault")
public record JvaultProperties(String tenant,
                               String baseUrl,
                               Jira jira,
                               Storage storage,
                               Crypto crypto,
                               List<Policy> policies,
                               Kafka kafka,
                               Dev dev) {

    public JvaultProperties {
        tenant = tenant == null ? "default" : tenant;
        baseUrl = baseUrl == null ? "http://localhost:8080" : baseUrl;
        jira = jira == null ? new Jira(null, null, null, null, null) : jira;
        storage = storage == null ? new Storage(null, null, null) : storage;
        crypto = crypto == null ? new Crypto(null) : crypto;
        policies = policies == null ? List.of() : List.copyOf(policies);
        kafka = kafka == null ? new Kafka(false, null, null, null, null) : kafka;
        dev = dev == null ? new Dev(false, null, null) : dev;
    }

    /**
     * @param deploymentId the identifier policies select on, not a URL. A deployment that is
     *                     re-pointed at a different site keeps its policies only if this stays
     * @param email        the service account's address; with {@code apiToken} it forms Basic
     *                     auth. A service account rather than a person's token, so that the
     *                     integration does not stop working when someone leaves (decision D4)
     */
    public record Jira(String deploymentId,
                       String baseUrl,
                       String email,
                       String apiToken,
                       String connectUrl) {
    }

    /**
     * @param root           where the filesystem backend writes. Never inside the working
     *                       directory
     * @param maxUploadBytes the largest attachment accepted. A number somebody chose: the
     *                       default is Jira Cloud's own per-file limit, so a file jvault accepts
     *                       is one Jira would have accepted too, and a policy change later
     *                       cannot strand a file that is too big to mirror
     */
    public record Storage(String route, Path root, Long maxUploadBytes) {

        public Storage {
            route = route == null ? "fs-local" : route;
            maxUploadBytes = maxUploadBytes == null ? 100L * 1024 * 1024 : maxUploadBytes;
        }
    }

    /** @param keyRings the rings policies may name. A policy naming an absent ring fails loudly */
    public record Crypto(List<String> keyRings) {

        public Crypto {
            keyRings = keyRings == null || keyRings.isEmpty()
                    ? List.of("default", "sec-restricted") : List.copyOf(keyRings);
        }
    }

    /**
     * One placement rule, as a deployment writes it.
     *
     * <p>Every selector field is optional and a null one matches everything, which is what makes
     * a broad rule short. The resolver decides ties by specificity rather than by order, so a
     * rule for one field beats a rule for the whole project however they are listed here
     * (docs/05-content-placement.md).
     *
     * @param surrogate what Jira shows in place of content that left. Required for an external
     *                  placement: Jira showing an empty field would be a worse lie than Jira
     *                  showing a link
     */
    public record Policy(String id,
                         String deployment,
                         String project,
                         String issueType,
                         String partType,
                         String field,
                         String placement,
                         String classification,
                         String storageRoute,
                         String keyRing,
                         String surrogate,
                         List<String> links,
                         Boolean allowOverride) {

        public Policy {
            placement = placement == null ? "EXTERNAL" : placement;
            classification = classification == null ? "INTERNAL" : classification;
            storageRoute = storageRoute == null ? "fs-local" : storageRoute;
            keyRing = keyRing == null ? "default" : keyRing;
            links = links == null || links.isEmpty() ? List.of("REMOTE_LINK") : List.copyOf(links);
            allowOverride = allowOverride != null && allowOverride;
        }
    }

    /**
     * Kafka ingestion.
     *
     * @param enabled        off unless asked for. A consumer that starts because nobody turned
     *                       it off is a consumer creating tickets nobody expected
     * @param mappingsFile   the YAML that says what messages mean. No mappings, no ingestion:
     *                       there is no sensible default for what a stranger's event becomes
     * @param deadLetterSuffix appended to a mapping's topic to name its dead-letter topic
     * @param quarantineKeyRing the ring that encrypts rejected payloads, which are content
     */
    public record Kafka(boolean enabled,
                        String bootstrapServers,
                        String mappingsFile,
                        String deadLetterSuffix,
                        String quarantineKeyRing) {

        public Kafka {
            deadLetterSuffix = deadLetterSuffix == null ? ".dlq" : deadLetterSuffix;
            quarantineKeyRing = quarantineKeyRing == null ? "sec-restricted" : quarantineKeyRing;
        }
    }

    /**
     * Development conveniences, every one of which is a hole in something.
     *
     * @param insecureAuth trust an identity header instead of authenticating. Off unless asked
     *                     for, because a mode this permissive should not be reachable by
     *                     forgetting a setting
     */
    /**
     * @param bootstrapProject the one project the development user is granted rights on at
     *                         startup, or {@code null} for none. Deliberately one project rather
     *                         than all of them: the point of running the real authorization path
     *                         in development is to notice when it refuses something it should not
     */
    public record Dev(boolean insecureAuth, String defaultUser, String bootstrapProject) {

        public Dev {
            defaultUser = defaultUser == null ? "dev-user" : defaultUser;
        }
    }
}
