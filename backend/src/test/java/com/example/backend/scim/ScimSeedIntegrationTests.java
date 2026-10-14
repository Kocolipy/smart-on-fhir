package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.ScimTestRows;
import com.example.backend.scim.application.ScimSeedService;
import com.example.backend.scim.application.ScimSeedService.DevFixture;
import com.example.backend.scim.application.ScimSeedService.SeededIdentity;
import com.example.backend.scim.domain.ScimSeedLock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeding against a real SQL Server, where a failed write cannot be caught and carried on from.
 *
 * <p>Exists because the unit tests run seeding against in-memory repositories, which throw a
 * duplicate-name exception without any transaction state. A seed that relied on catching that
 * exception would pass every one of them and still fail every restart against a seeded database:
 * SQL Server rolls back only the failed INSERT, but the exception crossing the repository's
 * transactional boundary marks the Spring transaction rollback-only, and Hibernate's session is
 * not to be flushed again after it, so the commit throws and the {@code ApplicationRunner} takes
 * Boot down.
 *
 * <p>The context's own startup has already seeded this database once, so every call here is the
 * restart case, but for the racing seeds, which seed fixtures of their own.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimSeedIntegrationTests {

    private static final String COUNT_RESOURCES = "SELECT count(*) FROM scim_resources";

    private static final String COUNT_MEMBERSHIPS = "SELECT count(*) FROM scim_group_members";

    private static final String COUNT_AUDIT_EVENTS = "SELECT count(*) FROM audit_events";

    /**
     * A session waiting on an application lock shows as a waiting {@code APPLICATION} request.
     * Read as the server's administrator: another login's locks are visible only with
     * {@code VIEW SERVER STATE}, which the service's logins do not hold.
     */
    private static final String COUNT_WAITING_APPLICATION_LOCKS = """
            SELECT count(*) FROM sys.dm_tran_locks
             WHERE resource_type = 'APPLICATION' AND request_status = 'WAIT'
               AND resource_database_id = DB_ID()""";

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    /** The fixture password this class seeds with: a test value, never a deployment's. */
    private static final String FIXTURE_PASSWORD = "seed-race-fixture-password";

    @Autowired
    private ScimSeedService seeding;

    @Autowired
    private ScimSeedLock seedLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    @Value("${app.auth.bootstrap-username}")
    private String recoveryUserName;

    @Value("${app.auth.bootstrap-password}")
    private String recoveryPassword;

    @Test
    void seeding_an_already_seeded_database_succeeds_and_writes_nothing() {
        long resources = count(COUNT_RESOURCES);
        long memberships = count(COUNT_MEMBERSHIPS);
        long auditEvents = count(COUNT_AUDIT_EVENTS);

        assertThatCode(this::seedAsConfigured)
                .as("a restart against a seeded database must not fail startup")
                .doesNotThrowAnyException();

        assertThat(count(COUNT_RESOURCES)).isEqualTo(resources);
        assertThat(count(COUNT_MEMBERSHIPS)).isEqualTo(memberships);
        assertThat(count(COUNT_AUDIT_EVENTS)).isEqualTo(auditEvents);
    }

    /**
     * A transaction-owned application lock taken on an auto-commit connection is released when
     * its own statement ends, so a call outside a transaction would protect nothing while appearing to
     * work. It is refused instead.
     */
    @Test
    void the_seeding_lock_cannot_be_taken_outside_a_transaction() {
        assertThatThrownBy(seedLock::acquire)
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Two instances starting together: while one holds the seeding lock, the other's seed waits
     * in the database rather than reading and racing it, and proceeds once the holder commits.
     *
     * <p>"Waits" is observed in {@code sys.dm_tran_locks}, not inferred from a sleep, so the
     * assertion does not depend on timing.
     */
    @Test
    void a_seed_waits_for_a_concurrent_holder_of_the_seeding_lock() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> transaction.executeWithoutResult(status -> {
                seedLock.acquire();
                held.countDown();
                awaitQuietly(release);
            }));
            assertThat(held.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            Future<?> seed = threads.submit(this::seedAsConfigured);
            awaitWaitingApplicationLocks(1);
            assertThat(seed.isDone())
                    .as("the seed must be blocked while another transaction holds the lock")
                    .isFalse();

            release.countDown();
            holder.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThatCode(() -> seed.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .doesNotThrowAnyException();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    /**
     * Two instances seeding the same fixtures together, as two starts against one fresh database
     * would: the second waits on the seeding lock — a waiting application lock in
     * {@code sys.dm_tran_locks} — and once the first has committed it reads what the first seeded
     * and writes nothing. The fixture is seeded once: one User, one Group, one membership. Without
     * the lock the second would read "absent" too, and its create would collide with the first's.
     */
    @Test
    void two_seeds_racing_seed_once_with_the_second_waiting_on_the_seeding_lock() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        DevFixture fixture = new DevFixture(UUID.randomUUID(), "Seed race " + tag, "seed-race-" + tag);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch seeded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> transaction.executeWithoutResult(status -> {
                seeding.seedDevFixtures(List.of(fixture), null, FIXTURE_PASSWORD);
                seeded.countDown();
                awaitQuietly(release);
            }));
            assertThat(seeded.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            Future<?> second = threads.submit(() ->
                    seeding.seedDevFixtures(List.of(fixture), null, FIXTURE_PASSWORD));
            awaitWaitingApplicationLocks(1);

            release.countDown();
            first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThatCode(() -> second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .as("the second seed reads the first's and writes nothing")
                    .doesNotThrowAnyException();

            assertThat(List.of(
                    count("SELECT count(*) FROM scim_users WHERE user_name = ?", fixture.member()),
                    count("SELECT count(*) FROM scim_groups WHERE resource_id = ?",
                            fixture.groupId()),
                    count("SELECT count(*) FROM scim_group_members WHERE group_id = ?",
                            fixture.groupId())))
                    .as("the User, the Group and the membership, each seeded once")
                    .containsExactly(1L, 1L, 1L);
        } finally {
            release.countDown();
            threads.shutdownNow();
            ScimTestRows.deleteUnreserved(jdbc, fixture.groupId());
            jdbc.queryForList("SELECT resource_id FROM scim_users WHERE user_name = ?",
                            UUID.class, fixture.member())
                    .forEach(user -> ScimTestRows.deleteUnreserved(jdbc, user));
        }
    }

    private void awaitWaitingApplicationLocks(long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (logins.superuser().jdbc().queryForObject(COUNT_WAITING_APPLICATION_LOCKS, Long.class)
                != expected) {
            assertThat(Instant.now())
                    .as("no seed came to wait on the seeding lock")
                    .isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void seedAsConfigured() {
        seeding.seed(new SeededIdentity(recoveryUserName, recoveryPassword));
    }

    private long count(String sql, Object... arguments) {
        Long counted = jdbc.queryForObject(sql, Long.class, arguments);
        return counted == null ? 0 : counted;
    }
}
