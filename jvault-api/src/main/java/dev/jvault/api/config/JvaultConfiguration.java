package dev.jvault.api.config;

import dev.jvault.api.auth.JiraUserAccessChecker;
import dev.jvault.api.content.ContentAccessController;
import dev.jvault.api.idempotency.IdempotencyService;
import dev.jvault.api.idempotency.InMemoryIdempotencyStore;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.api.security.HeaderCallerResolver;
import dev.jvault.api.security.SessionCallerResolver;
import dev.jvault.authz.ContentAuthorizationService;
import dev.jvault.authz.Grant;
import dev.jvault.authz.GrantService;
import dev.jvault.authz.Permission;
import dev.jvault.authz.Principal;
import dev.jvault.authz.Scope;
import dev.jvault.authz.SpacePermissionMode;
import dev.jvault.authz.session.SessionStore;
import dev.jvault.content.CommentRepository;
import dev.jvault.content.ContentMetadataRepository;
import dev.jvault.content.ContentService;
import dev.jvault.content.LinkFactory;
import dev.jvault.content.TicketAmendmentService;
import dev.jvault.content.TicketCreationService;
import dev.jvault.content.TicketRepository;
import dev.jvault.crypto.envelope.ContentCipher;
import dev.jvault.crypto.kms.KeyManagementService;
import dev.jvault.crypto.kms.LocalKeyManagementService;
import dev.jvault.crypto.text.SensitiveTextCipher;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.placement.LinkPlacement;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.Placement;
import dev.jvault.domain.placement.PlacementPolicy;
import dev.jvault.domain.placement.PolicySelector;
import dev.jvault.domain.common.SensitiveValue;
import dev.jvault.domain.placement.PolicySet;
import dev.jvault.domain.placement.SurrogateSpec;
import dev.jvault.jira.deployment.CloudDeployment;
import dev.jvault.jira.deployment.JiraCredentials;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.gateway.HttpJiraMetadataGateway;
import dev.jvault.jira.gateway.JdkJiraHttpClient;
import dev.jvault.jira.gateway.JiraHttpClient;
import dev.jvault.jira.gateway.JiraMetadataGateway;
import dev.jvault.jira.oauth.JiraOAuthClient;
import dev.jvault.outbox.OutboxRepository;
import dev.jvault.persistence.DialectDetector;
import dev.jvault.persistence.JdbcAclRepository;
import dev.jvault.persistence.JdbcCommentRepository;
import dev.jvault.persistence.JdbcContentMetadataRepository;
import dev.jvault.persistence.JdbcOutboxRepository;
import dev.jvault.persistence.JdbcSessionStore;
import dev.jvault.persistence.JdbcTicketRepository;
import dev.jvault.persistence.SchemaMigrator;
import dev.jvault.persistence.SqlDialect;
import dev.jvault.storage.filesystem.FilesystemContentStore;
import dev.jvault.storage.spi.ContentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The composition root: the one place that knows which adapter implements which port.
 *
 * <p>Everything below it is written against interfaces and has no idea whether content is on a
 * filesystem or in S3, whether grants are in PostgreSQL or Oracle, or whether Jira is Cloud or
 * Data Center. That is the property that makes the same application core serve the REST API and
 * the Kafka consumer identically (docs/06-rest-api.md 6.3), and it survives only if the knowledge
 * stays here.
 */
@Configuration
@EnableConfigurationProperties(JvaultProperties.class)
public class JvaultConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JvaultConfiguration.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // --- persistence ------------------------------------------------------------

    @Bean
    public SqlDialect sqlDialect(DataSource dataSource) {
        SqlDialect dialect = DialectDetector.detect(dataSource);
        if (!dialect.verifiedByIntegrationTests()) {
            // Said plainly at startup rather than buried in a document nobody reads during an
            // incident. The dialect works or it does not; what is unknown is whether anyone has
            // ever checked (decision D5).
            log.warn("Running on the {} dialect, which no integration test in this repository "
                    + "has exercised. Its claim-and-lock behaviour is unproven.", dialect.id());
        }
        return dialect;
    }

    @Bean
    public SchemaMigrator schemaMigrator(DataSource dataSource, SqlDialect dialect) {
        var migrator = new SchemaMigrator(dataSource, dialect);
        migrator.migrate();
        return migrator;
    }

    @Bean
    public OutboxRepository outboxRepository(DataSource dataSource, SqlDialect dialect,
                                             SchemaMigrator migrated) {
        return new JdbcOutboxRepository(dataSource, dialect);
    }

    @Bean
    public TicketRepository ticketRepository(DataSource dataSource, SchemaMigrator migrated) {
        return new JdbcTicketRepository(dataSource);
    }

    @Bean
    public ContentMetadataRepository contentMetadataRepository(DataSource dataSource,
                                                               SensitiveTextCipher names,
                                                               SchemaMigrator migrated) {
        return new JdbcContentMetadataRepository(dataSource, names);
    }

    @Bean
    public CommentRepository commentRepository(DataSource dataSource, SchemaMigrator migrated) {
        return new JdbcCommentRepository(dataSource);
    }

    @Bean
    public JdbcAclRepository aclRepository(DataSource dataSource, SchemaMigrator migrated) {
        return new JdbcAclRepository(dataSource);
    }

    // --- cryptography and storage -----------------------------------------------

    /**
     * Keys held in this process. <strong>Development only.</strong>
     *
     * <p>A key management service that keeps its keys in the same memory as the ciphertext offers
     * no separation at all: anyone who can read a heap dump has both halves. A deployment
     * replaces this bean with one backed by a KMS or an HSM, and the warning says so every time
     * the application starts rather than once in a document.
     */
    @Bean
    public KeyManagementService keyManagementService(JvaultProperties properties) {
        List<String> rings = properties.crypto().keyRings();
        log.warn("Using the in-process key manager with rings {}. Keys live in this JVM's memory, "
                + "so encryption at rest protects against a stolen disk and nothing else. "
                + "Replace this bean before storing anything real.", rings);
        return LocalKeyManagementService.withKeyRings(rings.toArray(String[]::new));
    }

    @Bean
    public ContentCipher contentCipher(KeyManagementService kms) {
        return new ContentCipher(kms);
    }

    @Bean
    public SensitiveTextCipher sensitiveTextCipher(KeyManagementService kms) {
        return new SensitiveTextCipher(kms);
    }

    @Bean
    public ContentStore contentStore(JvaultProperties properties) {
        Path root = properties.storage().root() == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "jvault-content")
                : properties.storage().root();
        return new FilesystemContentStore(properties.storage().route(), root);
    }

    // --- application services ---------------------------------------------------

    @Bean
    public LinkFactory linkFactory(JvaultProperties properties) {
        return new LinkFactory(properties.baseUrl());
    }

    @Bean
    public ContentService contentService(ContentCipher cipher,
                                         ContentStore store,
                                         ContentMetadataRepository metadata,
                                         Clock clock,
                                         JvaultProperties properties) {
        return new ContentService(cipher, store, metadata, ContentService.IdGenerator.random(),
                clock, properties.tenant());
    }

    /**
     * The placement policies.
     *
     * <p>Empty when none are configured, which means everything goes to Jira — the behaviour of
     * not having jvault at all. That is the right default: a policy set that guessed would move
     * somebody's data out of Jira because nobody configured it, and an empty set fails visibly
     * rather than silently.
     *
     * <p>{@link PolicySet#of} validates what it is given, so a policy that externalises content
     * without naming a surrogate, or that uses a surrogate token nobody defined, stops the
     * application here rather than producing a Jira issue with a hole in it.
     */
    @Bean
    public PolicySet placementPolicies(JvaultProperties properties) {
        if (properties.policies().isEmpty()) {
            log.info("No placement policies configured; every field goes to Jira.");
            return PolicySet.empty();
        }
        var policies = new java.util.ArrayList<PlacementPolicy>();
        for (JvaultProperties.Policy configured : properties.policies()) {
            policies.add(toPolicy(configured));
        }
        log.info("Loaded {} placement policies.", policies.size());
        return PolicySet.of(policies);
    }

    private static PlacementPolicy toPolicy(JvaultProperties.Policy configured) {
        var links = new java.util.LinkedHashSet<LinkPlacement>();
        for (String link : configured.links()) {
            links.add(LinkPlacement.valueOf(link));
        }
        Placement placement = Placement.valueOf(configured.placement());

        return PlacementPolicy.builder()
                .id(configured.id())
                .selector(new PolicySelector(
                        configured.deployment(),
                        configured.project(),
                        configured.issueType(),
                        configured.partType() == null
                                ? null : PartType.valueOf(configured.partType()),
                        configured.field()))
                .placement(placement)
                .classification(Classification.valueOf(configured.classification()))
                .storageRoute(configured.storageRoute())
                .keyRing(configured.keyRing())
                // Not configurable, and not an oversight: content that has left Jira because it
                // was too sensitive for Jira does not then get written to disk in the clear.
                .encryptionRequired(true)
                .surrogate(configured.surrogate() == null
                        ? null : SurrogateSpec.placeholder(configured.surrogate()))
                .linkPlacements(links)
                .allowOverride(configured.allowOverride())
                .build();
    }

    @Bean
    public TicketCreationService ticketCreationService(PolicySet policies,
                                                       ContentService content,
                                                       TicketRepository tickets,
                                                       OutboxRepository outbox,
                                                       LinkFactory links,
                                                       Clock clock) {
        return new TicketCreationService(policies, content, tickets, outbox, links,
                ContentService.IdGenerator.random(), clock);
    }

    /**
     * Idempotency keys, held in this process.
     *
     * <p>Correct on one node and wrong on two: a retry that lands on a different instance sees no
     * claim and creates a second ticket. The dedupe key in {@code ticket_record} is the durable
     * defence and does span nodes, so the failure mode is a duplicated HTTP response rather than
     * a duplicated Jira issue — but this belongs in the database before anyone runs a second
     * instance.
     */
    @Bean
    public IdempotencyService idempotencyService(Clock clock) {
        log.warn("Idempotency keys are held in memory. A retry that reaches a different instance "
                + "will not be recognised; run one instance until this is on the database.");
        return new IdempotencyService(new InMemoryIdempotencyStore(), clock);
    }

    @Bean
    public TicketAmendmentService ticketAmendmentService(PolicySet policies,
                                                         ContentService content,
                                                         TicketRepository tickets,
                                                         CommentRepository comments,
                                                         OutboxRepository outbox,
                                                         LinkFactory links,
                                                         Clock clock) {
        return new TicketAmendmentService(policies, content, tickets, comments, outbox, links,
                ContentService.IdGenerator.random(), clock);
    }

    // --- Jira -------------------------------------------------------------------

    @Bean
    public JiraDeployment jiraDeployment(JvaultProperties properties) {
        JvaultProperties.Jira jira = properties.jira();
        if (jira.baseUrl() == null || jira.apiToken() == null) {
            throw new IllegalStateException(
                    "jvault.jira.base-url and jvault.jira.api-token must be set. The token "
                            + "belongs in the environment, never in a configuration file that "
                            + "gets committed.");
        }
        // cloudId is unused when the site URL is given directly, which is how a deployment
        // reaching Jira through its own host rather than the Atlassian gateway is configured.
        return new CloudDeployment(
                jira.deploymentId() == null ? "jira-cloud" : jira.deploymentId(),
                "unused",
                JiraCredentials.basic(jira.email(), jira::apiToken),
                jira.baseUrl());
    }

    @Bean
    public JiraHttpClient jiraHttpClient(JiraDeployment deployment) {
        return new JdkJiraHttpClient(deployment);
    }

    @Bean
    public JiraMetadataGateway jiraMetadataGateway(JiraDeployment deployment,
                                                   JiraHttpClient http) {
        return new HttpJiraMetadataGateway(deployment, http);
    }

    @Bean
    public String deploymentId(JiraDeployment deployment) {
        return deployment.id();
    }

    // --- authorization ----------------------------------------------------------

    @Bean
    public ContentAuthorizationService contentAuthorizationService(
            JdbcAclRepository acl,
            ContentAuthorizationService.JiraAccessChecker jira,
            Clock clock) {
        return new ContentAuthorizationService(acl, jira, spaceSettings(), clock);
    }

    @Bean
    public GrantService grantService(JdbcAclRepository acl,
                                     ContentAuthorizationService authorization,
                                     Clock clock) {
        return new GrantService(authorization, acl, clock);
    }

    /**
     * The live half of the INTERSECT rule: does this person still have the issue in Jira.
     *
     * <p>jvault's own grants are necessary and never sufficient. A checker that answered "yes"
     * unconditionally would not weaken that rule so much as delete half of it, leaving a vault
     * whose access control is whatever somebody once wrote into a grant table
     * (docs/11-authorization.md).
     *
     * <p>So there is no default. A deployment supplies one, or the application refuses to start —
     * the same treatment as authentication, and for the same reason: the permissive version of
     * this must not be what you get by leaving something unconfigured.
     */
    @Bean
    public ContentAuthorizationService.JiraAccessChecker jiraAccessChecker(
            JvaultProperties properties,
            org.springframework.beans.factory.ObjectProvider<JiraOAuthClient> oauth,
            SessionStore sessions,
            JiraDeployment deployment,
            Clock clock) {
        JiraOAuthClient client = oauth.getIfAvailable();
        if (client != null) {
            // The real thing at last: Jira's own answer, asked with this person's token. Which
            // is what signing in with Atlassian was the prerequisite for.
            log.info("Content access is checked against Jira as the requesting user.");
            return new JiraUserAccessChecker(sessions, client, deployment.id(), clock);
        }
        if (!properties.dev().insecureAuth()) {
            throw new IllegalStateException(
                    "No Jira access checker is configured. Content authorization is the "
                            + "intersection of a jvault grant and a live Jira permission check, "
                            + "and the second half is missing. Provide a JiraAccessChecker bean, "
                            + "or set jvault.dev.insecure-auth=true to run without one.");
        }
        log.warn("INSECURE: the Jira half of the authorization check always allows. Access to "
                + "content is whatever the grant table says, with nothing verifying it against "
                + "Jira. Development only.");
        return new ContentAuthorizationService.JiraAccessChecker() {
            @Override
            public Access canBrowse(Principal user, Scope scope) {
                return Access.ALLOWED;
            }

            @Override
            public boolean lastKnownGoodAllow(Principal user, Scope scope, Instant since) {
                return true;
            }
        };
    }

    /**
     * Gives the development user something to work with.
     *
     * <p>Only under the development flag, and only for the one project the profile names. A
     * bootstrap that granted broadly would be indistinguishable from having no authorization,
     * and the point of running the real authorization path in development is to notice when it
     * refuses something it should not.
     */
    @Bean
    public ApplicationRunner developmentGrants(JvaultProperties properties,
                                               JdbcAclRepository acl,
                                               Clock clock) {
        return args -> {
            if (!properties.dev().insecureAuth() || properties.dev().bootstrapProject() == null) {
                return;
            }
            String spaceId = properties.jira().deploymentId() + "/"
                    + properties.dev().bootstrapProject();
            Scope space = Scope.space(spaceId);
            Principal user = Principal.user(properties.dev().defaultUser());

            boolean alreadyGranted = acl.grantsAt(space).stream()
                    .anyMatch(existing -> existing.principal().equals(user));
            if (alreadyGranted) {
                return;
            }
            acl.save(new Grant(UUID.randomUUID(), space, user,
                    Set.of(Permission.VIEW, Permission.CREATE, Permission.EDIT,
                            Permission.DOWNLOAD),
                    false, 0, null, null, null, clock.instant()));
            log.warn("Granted {} full rights on {} because jvault.dev.bootstrap-project is set.",
                    user, spaceId);
        };
    }

    @Bean
    public ContentAuthorizationService.SpaceSettings spaceSettings() {
        return new ContentAuthorizationService.SpaceSettings() {
            @Override
            public SpacePermissionMode modeOf(String spaceId) {
                return SpacePermissionMode.INTERSECT;
            }

            @Override
            public Duration degradedGrace(String spaceId) {
                return Duration.ZERO;
            }
        };
    }

    // --- identity ---------------------------------------------------------------

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "jvault.oauth", name = "enabled", havingValue = "true")
    public JiraOAuthClient jiraOAuthClient(JvaultProperties properties) {
        JvaultProperties.Oauth oauth = properties.oauth();
        if (oauth.clientId() == null || oauth.clientSecret() == null
                || oauth.redirectUri() == null) {
            throw new IllegalStateException(
                    "jvault.oauth.enabled is true but client-id, client-secret or redirect-uri "
                            + "is missing. The redirect URI must match what is registered in the "
                            + "Atlassian developer console exactly.");
        }
        return new JiraOAuthClient(oauth.clientId(),
                SensitiveValue.of(oauth.clientSecret(), "jira.oauth.clientSecret"),
                oauth.redirectUri());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "jvault.oauth", name = "allow-manual-token", havingValue = "true")
    public dev.jvault.jira.oauth.JiraCredentialValidator jiraCredentialValidator() {
        return new dev.jvault.jira.oauth.JiraCredentialValidator();
    }

    @Bean
    public SessionStore sessionStore(DataSource dataSource, SensitiveTextCipher cipher,
                                     JvaultProperties properties, SchemaMigrator migrated) {
        return new JdbcSessionStore(dataSource, cipher, properties.crypto().keyRings().get(0));
    }

    @Bean
    public CallerResolver callerResolver(JvaultProperties properties, SessionStore sessions,
                                         JiraDeployment deployment, Clock clock) {
        if (properties.oauth().enabled()) {
            log.info("Signing in with Atlassian. Identities are Atlassian account ids.");
            return new SessionCallerResolver(sessions, deployment.id(), clock);
        }
        if (!properties.dev().insecureAuth()) {
            throw new IllegalStateException(
                    "No authentication is configured. Set jvault.dev.insecure-auth=true to run "
                            + "with the development header resolver, or provide a CallerResolver "
                            + "bean backed by your identity provider. Refusing to start with no "
                            + "authentication at all.");
        }
        log.warn("INSECURE: identity is taken from the {} header. Anyone who can reach this "
                        + "service can be anyone. Development only.",
                HeaderCallerResolver.USER_HEADER);
        return HeaderCallerResolver.forDevelopmentOnly(properties.dev().defaultUser());
    }

    /** Access to content is audited whether it succeeds or fails; a denial is the interesting one. */
    @Bean
    public ContentAccessController.AccessAuditor accessAuditor() {
        Logger audit = LoggerFactory.getLogger("jvault.audit");
        return (Caller caller, String contentRef, Permission permission, String reason) ->
                audit.info("access caller={} content={} permission={} outcome={}",
                        caller, contentRef, permission, reason);
    }
}
