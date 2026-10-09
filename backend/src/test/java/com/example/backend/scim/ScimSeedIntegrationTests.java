package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.application.ScimSeedService;
import com.example.backend.scim.application.ScimSeedService.SeededIdentity;
import com.example.backend.scim.domain.ScimSeedLock;
import java.time.Duration;
import java.time.Instant;
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
 * Seeding against a real Postgres, where a failed statement poisons the transaction it ran in.
 *
 * <p>Exists because the unit tests run seeding against in-memory repositories, which throw a
 * duplicate-name exception without any transaction state. A seed that relied on catching that
 * exception passed every one of them and still failed every restart against a seeded database:
 * the failed INSERT aborted the Postgres transaction and marked the Spring one rollback-only, so
 * the next statement — or the commit — threw and the {@code ApplicationRunner} took Boot down.
 *
 * <p>The context's own startup has already seeded this database once, so every call here is the
 * restart case.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimSeedIntegrationTests {

    private static final String COUNT_RESOURCES = "SELECT count(*) FROM scim_resources";

    private static final String COUNT_MEMBERSHIPS = "SELECT count(*) FROM scim_group_members";

    private static final String COUNT_AUDIT_EVENTS = "SELECT count(*) FROM audit_events";

    /** A backend waiting on an advisory lock shows as an ungranted advisory row. */
    private static final String COUNT_WAITING_ADVISORY_LOCKS =
            "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted";

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @Autowired
    private ScimSeedService seeding;

    @Autowired
    private ScimSeedLock seedLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

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
     * A transaction-scoped advisory lock taken on an auto-commit connection is released when its
     * own statement ends, so a call outside a transaction would protect nothing while appearing to
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
     * <p>"Waits" is observed in {@code pg_locks}, not inferred from a sleep, so the assertion does
     * not depend on timing.
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
            awaitWaitingAdvisoryLocks(1);
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

    private void awaitWaitingAdvisoryLocks(long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (count(COUNT_WAITING_ADVISORY_LOCKS) != expected) {
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

    private long count(String sql) {
        Long counted = jdbc.queryForObject(sql, Long.class);
        return counted == null ? 0 : counted;
    }
}
