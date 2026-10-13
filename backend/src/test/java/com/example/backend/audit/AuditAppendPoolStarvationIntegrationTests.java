package com.example.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A fail-open append never waits for the main connection pool (ADR 0004, 2026-10-13 addendum).
 *
 * <p>The starvation it closes: a refusal is recorded while its caller still holds a connection —
 * a rejected Login holds one, and the User row's lock, while it appends {@code LOGIN_FAILURE} —
 * and an append in a transaction of its own needed a second connection from the same pool. With
 * every connection held by such a caller, each waited the pool's whole connection timeout for a
 * connection none of them would give back, and the event was then lost behind an alert.
 *
 * <p>Here the main pool is two connections with a five-second timeout, and both are held, each by
 * a transaction that has used its connection. From inside one of them a refusal is appended: it
 * completes well inside that timeout, and the event is in the trail. A context of its own, because
 * the starvation needs a pool small enough to exhaust.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=5000"})
@Import(ContainerTestConfiguration.class)
class AuditAppendPoolStarvationIntegrationTests {

    /** The main pool's size: every one of its connections is held while the append runs. */
    private static final int MAIN_POOL_SIZE = 2;

    /** Well inside the main pool's five-second timeout, which a starved append waits out. */
    private static final Duration PROMPTLY = Duration.ofSeconds(2);

    @Autowired
    private AuditTrail audit;

    @Autowired
    private AuditEventReader events;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aFailOpenAppendCompletesPromptlyWhileEveryMainPoolConnectionIsHeld() throws Exception {
        UUID administrator = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(MAIN_POOL_SIZE);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(MAIN_POOL_SIZE);
        try {
            // Every main-pool connection but the appender's, held by a transaction that used it.
            List<Future<?>> holders = new ArrayList<>();
            for (int holder = 1; holder < MAIN_POOL_SIZE; holder++) {
                holders.add(callers.submit(() -> inTransaction(() -> {
                    jdbc.queryForObject("SELECT 1", Integer.class);
                    held.countDown();
                    await(release);
                })));
            }
            // The appender: holds the last connection, as a refusing caller does, and appends.
            Future<Duration> appended = callers.submit(() -> {
                Duration[] took = new Duration[1];
                inTransaction(() -> {
                    jdbc.queryForObject("SELECT 1", Integer.class);
                    held.countDown();
                    await(held);
                    long started = System.nanoTime();
                    audit.append(new Audited.UnlockRefused(
                            administrator, UUID.randomUUID(), AuditAdministrativeRefusal.SELF_TARGET));
                    took[0] = Duration.ofNanos(System.nanoTime() - started);
                });
                return took[0];
            });

            Duration took = appended.get(30, TimeUnit.SECONDS);
            release.countDown();
            for (Future<?> holder : holders) {
                holder.get(30, TimeUnit.SECONDS);
            }

            List<AuditEvent> recorded = events.find(new AuditEventQuery(
                    AuditOperation.LOCKOUT_LIFT, null, administrator, null, null, null, 0, 10))
                    .events();
            assertThat(List.of(took.compareTo(PROMPTLY) < 0, recorded.size()))
                    .as("whether the append beat %s (it took %s), and the events it recorded",
                            PROMPTLY, took)
                    .containsExactly(true, 1);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("a caller never reached the starved state");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
