package dev.jvault.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jvault.jira.egress.JiraOperation;
import dev.jvault.outbox.OutboxEntry;
import dev.jvault.outbox.OutboxState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox against a real PostgreSQL.
 *
 * <p>The contract properties asserted here are the ones the in-memory fake only <em>claims</em> to
 * reproduce. If {@code claim} does not genuinely skip locked rows, every test in
 * {@code OutboxDispatcherTest} still passes and production dispatches each effect to Jira twice.
 */
// disabledWithoutDocker: the suite stays green on a machine without a usable Docker, rather
// than failing for an environmental reason and training people to ignore red builds. The cost
// is that a skipped test proves nothing, so DialectDetectorTest asserts the verification flags
// separately and the README says plainly where this has and has not been executed.
@Testcontainers(disabledWithoutDocker = true)
class JdbcOutboxRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource dataSource;
    private static SqlDialect dialect;

    private JdbcOutboxRepository repository;
    private Instant now;

    @BeforeAll
    static void startDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);

        dialect = DialectDetector.detect(dataSource);
        new SchemaMigrator(dataSource, dialect).migrate();
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.close();
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM jira_outbox");
        }
        repository = new JdbcOutboxRepository(dataSource, dialect);
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    @DisplayName("the detected dialect is PostgreSQL, and it is the verified one")
    void dialectIsDetected() {
        assertThat(dialect.id()).isEqualTo("postgresql");
        assertThat(dialect.verifiedByIntegrationTests()).isTrue();
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("every field survives a write and a read")
        void allFieldsRoundTrip() {
            OutboxEntry entry = entry("create-issue", "issue:10001").rescheduled(
                    now.plusSeconds(30), "JIRA_503");

            repository.append(entry);
            OutboxEntry loaded = repository.find(entry.id()).orElseThrow();

            assertThat(loaded.id()).isEqualTo(entry.id());
            assertThat(loaded.ticketRef()).isEqualTo(entry.ticketRef());
            assertThat(loaded.deploymentId()).isEqualTo(entry.deploymentId());
            assertThat(loaded.issueLane()).isEqualTo("issue:10001");
            assertThat(loaded.operation()).isEqualTo(JiraOperation.CREATE_ISSUE);
            assertThat(loaded.effectKey()).isEqualTo("create-issue");
            assertThat(loaded.identityRef()).isEqualTo("INTEGRATION:svc-jvault");
            assertThat(loaded.state()).isEqualTo(OutboxState.FAILED);
            assertThat(loaded.lastErrorCode()).isEqualTo("JIRA_503");
            assertThat(loaded.nextAttemptAt()).isEqualTo(now.plusSeconds(30));
            assertThat(loaded.createdAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("payload references survive, including awkward characters")
        void payloadRefRoundTrips() {
            var payload = Map.of(
                    "projectKey", "SEC",
                    "correlationId", "corr-8f21c",
                    "expectedSummary", "[HIGH] Alert = odd\nsecond line",
                    "escaped", "back\\slash");

            repository.append(OutboxEntry.pending("t1", "dep", "issue:1",
                    JiraOperation.CREATE_ISSUE, "create-issue", payload,
                    "INTEGRATION:svc", now));

            assertThat(repository.findByEffect("t1", "create-issue").orElseThrow().payloadRef())
                    .isEqualTo(payload);
        }

        @Test
        @DisplayName("nullable columns round trip as null")
        void nullsRoundTrip() {
            OutboxEntry entry = entry("prop:jvault.origin", "issue:1");

            repository.append(entry);
            OutboxEntry loaded = repository.find(entry.id()).orElseThrow();

            assertThat(loaded.lastErrorCode()).isNull();
            assertThat(loaded.attemptStartedAt()).isNull();
        }

        @Test
        @DisplayName("save updates the mutable fields")
        void saveUpdates() {
            OutboxEntry entry = entry("create-issue", "issue:1");
            repository.append(entry);

            repository.save(entry.startingAttempt(now.plusSeconds(1), true));

            OutboxEntry loaded = repository.find(entry.id()).orElseThrow();
            assertThat(loaded.state()).isEqualTo(OutboxState.IN_FLIGHT);
            assertThat(loaded.attempts()).isEqualTo(1);
            assertThat(loaded.attemptStartedAt()).isEqualTo(now.plusSeconds(1));
        }
    }

    @Nested
    @DisplayName("append is idempotent on (ticketRef, effectKey)")
    class Idempotency {

        @Test
        @DisplayName("appending the same effect twice yields one row and the original entry")
        void duplicateAppendReturnsExisting() {
            OutboxEntry first = entry("remote-link:c1", "issue:1");

            OutboxEntry a = repository.append(first);
            OutboxEntry b = repository.append(entry("remote-link:c1", "issue:1"));

            assertThat(a.id()).isEqualTo(first.id());
            assertThat(b.id())
                    .as("the second append must yield the existing row, not a new one")
                    .isEqualTo(first.id());
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same effect key on a different ticket is a different effect")
        void effectKeysAreScopedToTheTicket() {
            repository.append(OutboxEntry.pending("ticket-a", "dep", "issue:1",
                    JiraOperation.CREATE_ISSUE, "create-issue", Map.of(), "id", now));
            repository.append(OutboxEntry.pending("ticket-b", "dep", "issue:2",
                    JiraOperation.CREATE_ISSUE, "create-issue", Map.of(), "id", now));

            assertThat(rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("concurrent appends of one effect still produce exactly one row")
        void concurrentAppendsProduceOneRow() throws Exception {
            int threads = 12;
            List<Callable<OutboxEntry>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> repository.append(
                        OutboxEntry.pending("t-race", "dep", "issue:1",
                                JiraOperation.UPSERT_REMOTE_LINK, "remote-link:c1",
                                Map.of(), "id", now)));
            }

            List<OutboxEntry> results = runConcurrently(tasks);

            // A read-then-write check would race here and produce several rows; the unique
            // constraint is what actually makes this safe.
            assertThat(rowCount()).isEqualTo(1);
            assertThat(results).extracting(OutboxEntry::id).containsOnly(results.get(0).id());
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("claiming returns due entries oldest first and marks them CLAIMED")
        void claimsDueEntriesInOrder() {
            appendAt("first", now.minusSeconds(30));
            appendAt("second", now.minusSeconds(20));
            appendAt("third", now.minusSeconds(10));

            List<OutboxEntry> claimed = repository.claim(10, now);

            assertThat(claimed).extracting(OutboxEntry::effectKey)
                    .containsExactly("first", "second", "third");
            assertThat(claimed).allMatch(e -> e.state() == OutboxState.CLAIMED);
            assertThat(repository.findByEffect("t1", "first").orElseThrow().state())
                    .isEqualTo(OutboxState.CLAIMED);
        }

        @Test
        @DisplayName("the batch limit is respected")
        void respectsLimit() {
            for (int i = 0; i < 10; i++) {
                appendAt("effect-" + i, now.minusSeconds(60 - i));
            }

            assertThat(repository.claim(4, now)).hasSize(4);
        }

        @Test
        @DisplayName("entries scheduled for later are not claimed")
        void respectsNextAttemptAt() {
            repository.append(entry("future", "issue:1").rescheduled(now.plusSeconds(60), "X"));

            assertThat(repository.claim(10, now)).isEmpty();
            assertThat(repository.claim(10, now.plusSeconds(61))).hasSize(1);
        }

        @Test
        @DisplayName("terminal and in-flight entries are never claimed")
        void onlyClaimableStatesAreClaimed() {
            repository.append(entry("done", "issue:1"));
            repository.save(repository.findByEffect("t1", "done").orElseThrow().succeeded());

            repository.append(entry("gone", "issue:1"));
            repository.save(repository.findByEffect("t1", "gone").orElseThrow().abandoned("X"));

            repository.append(entry("sent", "issue:1"));
            repository.save(repository.findByEffect("t1", "sent").orElseThrow()
                    .startingAttempt(now, true));

            // IN_FLIGHT is held for the ambiguity resolver and must never be picked up again.
            assertThat(repository.claim(10, now)).isEmpty();
        }

        @Test
        @DisplayName("FAILED entries are retried once their backoff has elapsed")
        void failedEntriesBecomeClaimableAgain() {
            repository.append(entry("retry-me", "issue:1"));
            OutboxEntry claimed = repository.claim(10, now).get(0);
            repository.save(claimed.rescheduled(now.plusSeconds(2), "JIRA_503"));

            assertThat(repository.claim(10, now)).isEmpty();
            assertThat(repository.claim(10, now.plusSeconds(3))).hasSize(1);
        }

        @Test
        @DisplayName("two dispatchers never claim the same entry")
        void concurrentClaimsAreDisjoint() throws Exception {
            int total = 200;
            for (int i = 0; i < total; i++) {
                appendAt("effect-" + i, now.minusSeconds(300).plusMillis(i));
            }

            int workers = 8;
            List<Callable<List<OutboxEntry>>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                tasks.add(() -> {
                    var mine = new ArrayList<OutboxEntry>();
                    List<OutboxEntry> batch;
                    while (!(batch = repository.claim(7, now)).isEmpty()) {
                        mine.addAll(batch);
                    }
                    return mine;
                });
            }

            List<OutboxEntry> allClaimed = runConcurrently(tasks).stream()
                    .flatMap(List::stream)
                    .toList();

            Set<String> distinct = allClaimed.stream()
                    .map(e -> e.id().toString())
                    .collect(Collectors.toSet());

            // This is the property the whole dispatcher rests on. If SKIP LOCKED were dropped,
            // or the hints were wrong on another engine, the counts below diverge and jvault
            // writes the same effect to Jira more than once.
            assertThat(allClaimed)
                    .as("an entry claimed twice means an effect dispatched twice")
                    .hasSize(distinct.size());
            assertThat(distinct).hasSize(total);
            assertThat(repository.claim(10, now)).isEmpty();
        }
    }

    @Nested
    @DisplayName("recovery queries")
    class Recovery {

        @Test
        @DisplayName("in-flight entries older than a threshold are discoverable")
        void findsStuckInFlightEntries() {
            repository.append(entry("stuck", "issue:1"));
            OutboxEntry claimed = repository.claim(10, now).get(0);
            repository.save(claimed.startingAttempt(now.minus(Duration.ofMinutes(20)), true));

            assertThat(repository.findInFlightOlderThan(now.minus(Duration.ofMinutes(10))))
                    .extracting(OutboxEntry::effectKey)
                    .containsExactly("stuck");
            assertThat(repository.findInFlightOlderThan(now.minus(Duration.ofMinutes(30))))
                    .isEmpty();
        }

        @Test
        @DisplayName("claims abandoned by a dead dispatcher are released")
        void releasesStaleClaims() {
            appendAt("orphaned", now.minus(Duration.ofHours(2)));
            repository.claim(10, now);

            int released = repository.releaseStaleClaims(now.minus(Duration.ofHours(1)));

            assertThat(released).isEqualTo(1);
            assertThat(repository.claim(10, now)).hasSize(1);
        }

        @Test
        @DisplayName("in-flight entries are never released automatically")
        void inFlightIsNeverReleased() {
            appendAt("sent", now.minus(Duration.ofHours(2)));
            OutboxEntry claimed = repository.claim(10, now).get(0);
            repository.save(claimed.startingAttempt(now.minus(Duration.ofHours(2)), true));

            // CLAIMED means selected but not sent, so releasing is safe. IN_FLIGHT means the
            // request may already have reached Jira, and re-dispatching it could duplicate an
            // effect — that decision belongs to the ambiguity resolver, not to a sweep.
            assertThat(repository.releaseStaleClaims(now)).isZero();
            assertThat(repository.claim(10, now)).isEmpty();
        }
    }

    // --- helpers -----------------------------------------------------------------

    private OutboxEntry entry(String effectKey, String lane) {
        return OutboxEntry.pending("t1", "jira-cloud-prod", lane, JiraOperation.CREATE_ISSUE,
                effectKey, Map.of("projectKey", "SEC"), "INTEGRATION:svc-jvault", now);
    }

    private void appendAt(String effectKey, Instant createdAt) {
        repository.append(new OutboxEntry(java.util.UUID.randomUUID(), "t1", "dep", "issue:1",
                JiraOperation.CREATE_ISSUE, effectKey, Map.of(), "id",
                OutboxState.PENDING, 0, createdAt, null, null, createdAt));
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = pool.invokeAll(tasks);
            var results = new ArrayList<T>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private long rowCount() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM jira_outbox")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
