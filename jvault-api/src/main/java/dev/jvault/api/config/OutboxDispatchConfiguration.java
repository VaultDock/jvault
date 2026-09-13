package dev.jvault.api.config;

import dev.jvault.content.CommentRepository;
import dev.jvault.content.ContentMetadataRepository;
import dev.jvault.content.ContentService;
import dev.jvault.content.RepositoryTicketStateSink;
import dev.jvault.content.TicketPayloadAssembler;
import dev.jvault.content.TicketRepository;
import dev.jvault.domain.common.Classification;
import dev.jvault.jira.deployment.JiraDeployment;
import dev.jvault.jira.egress.EgressGuard;
import dev.jvault.jira.egress.PatternContentClassifier;
import dev.jvault.jira.gateway.HttpJiraWriteGateway;
import dev.jvault.jira.gateway.JiraHttpClient;
import dev.jvault.jira.gateway.JiraWriteGateway;
import dev.jvault.outbox.DispatchReport;
import dev.jvault.outbox.JiraPayloadAssembler;
import dev.jvault.outbox.OutboxDispatcher;
import dev.jvault.outbox.OutboxRepository;
import dev.jvault.outbox.TicketStateSink;
import dev.jvault.outbox.backoff.BackoffPolicy;
import dev.jvault.outbox.ratelimit.PerIssueRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * The thing that actually writes to Jira.
 *
 * <p>A ticket is created in two steps: the content is stored and an outbox row is written in one
 * transaction, and the Jira call happens afterwards from that row. That is what makes the create
 * safe to retry — but it also means that without something draining the outbox, every ticket
 * sits at JIRA_PENDING forever and nothing ever reaches Jira. The rows accumulate quietly, which
 * is the worst way for this to be broken.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "jvault.outbox", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxDispatchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchConfiguration.class);

    /** Large enough to make progress, small enough that one stuck pass strands little. */
    private static final int BATCH_SIZE = 25;

    /** Long enough that a slow pass is never mistaken for a dead one. */
    private static final Duration CLAIM_GRACE = Duration.ofMinutes(5);

    @Bean
    public JiraWriteGateway jiraWriteGateway(JiraDeployment deployment, JiraHttpClient http) {
        return new HttpJiraWriteGateway(deployment, http);
    }

    /**
     * The last check before anything leaves for Jira.
     *
     * <p>It compares what is about to be sent against what was stored externally, so a bug
     * anywhere upstream — a surrogate that did not render, a field that skipped the placement
     * resolver — is caught here rather than in Jira's issue history. Blocking at INTERNAL means
     * anything classified above "public" stops.
     */
    @Bean
    public EgressGuard egressGuard() {
        return new EgressGuard(PatternContentClassifier.withDefaults(), Classification.INTERNAL);
    }

    @Bean
    public JiraPayloadAssembler jiraPayloadAssembler(TicketRepository tickets,
                                                     CommentRepository comments,
                                                     ContentMetadataRepository metadata,
                                                     ContentService content) {
        return new TicketPayloadAssembler(tickets, comments, metadata, content);
    }

    @Bean
    public TicketStateSink ticketStateSink(TicketRepository tickets) {
        return new RepositoryTicketStateSink(tickets);
    }

    /**
     * Jira Cloud's published limits, minus a margin.
     *
     * <p>Being rate-limited is not a failure — the dispatcher backs off and retries — but it
     * costs a round trip and a delay to learn something the documentation already said.
     */
    @Bean
    public PerIssueRateLimiter perIssueRateLimiter() {
        return new PerIssueRateLimiter(
                new PerIssueRateLimiter.Window(8, Duration.ofSeconds(1)),
                new PerIssueRateLimiter.Window(400, Duration.ofMinutes(1)));
    }

    @Bean
    public OutboxDispatcher outboxDispatcher(OutboxRepository outbox,
                                             JiraPayloadAssembler assembler,
                                             EgressGuard egressGuard,
                                             JiraWriteGateway gateway,
                                             PerIssueRateLimiter rateLimiter,
                                             TicketStateSink ticketStates,
                                             Clock clock) {
        return new OutboxDispatcher(outbox, assembler, egressGuard, gateway, rateLimiter,
                BackoffPolicy.atlassianDefault(), ticketStates, clock, randomGenerator());
    }

    private static RandomGenerator randomGenerator() {
        // Jitter on the retry delay, so a hundred entries failing together do not all come back
        // at the same instant.
        return ThreadLocalRandom.current();
    }

    @Bean
    public OutboxPump outboxPump(OutboxDispatcher dispatcher, OutboxRepository outbox,
                                 Clock clock) {
        return new OutboxPump(dispatcher, outbox, clock);
    }

    /**
     * Drains the outbox on a timer.
     *
     * <p>A fixed delay rather than a fixed rate: the gap is measured from the end of one pass, so
     * a slow pass does not have the next one starting on top of it.
     */
    public static final class OutboxPump {

        private final OutboxDispatcher dispatcher;
        private final OutboxRepository outbox;
        private final Clock clock;

        OutboxPump(OutboxDispatcher dispatcher, OutboxRepository outbox, Clock clock) {
            this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
            this.outbox = Objects.requireNonNull(outbox, "outbox");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Scheduled(fixedDelayString = "${jvault.outbox.interval-ms:2000}",
                initialDelayString = "${jvault.outbox.initial-delay-ms:5000}")
        public void pump() {
            try {
                DispatchReport report = dispatcher.runOnce(BATCH_SIZE);
                if (!report.outcomes().isEmpty()) {
                    log.info("Outbox pass: {}", report.summary());
                }
            } catch (RuntimeException e) {
                // The scheduler cancels a task that throws, which would stop dispatching for the
                // life of the process and look exactly like nothing being wrong.
                log.error("Outbox dispatch pass failed; will try again next tick", e);
            }
        }

        /**
         * Returns rows that were claimed and never finished.
         *
         * <p>A pass claims a batch and then works through it. If that pass dies part-way — an
         * exception escaping it, a process killed, a node lost — everything it had claimed and
         * not yet dispatched stays CLAIMED, and CLAIMED rows are not picked up again. The queue
         * then looks empty while holding work nobody is doing, which is the worst shape for this
         * to fail in: no error, no backlog alarm, and no tickets reaching Jira.
         *
         * <p>The grace period is what separates "another node is working on it" from
         * "abandoned", so it must comfortably exceed how long a pass can take.
         */
        @Scheduled(fixedDelayString = "${jvault.outbox.recovery-interval-ms:60000}",
                initialDelayString = "${jvault.outbox.recovery-initial-delay-ms:30000}")
        public void releaseStaleClaims() {
            try {
                int released = outbox.releaseStaleClaims(clock.instant().minus(CLAIM_GRACE));
                if (released > 0) {
                    log.warn("Released {} outbox entries that were claimed and never finished",
                            released);
                }
            } catch (RuntimeException e) {
                log.error("Could not release stale outbox claims", e);
            }
        }
    }
}
