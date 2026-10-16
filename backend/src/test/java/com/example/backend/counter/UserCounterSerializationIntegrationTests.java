package com.example.backend.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.ScimTestRows;
import com.example.backend.counter.application.UserCounterService;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The counter lock against a real SQL Server under read-committed snapshot (ADR 0015): a write
 * of a User's counter reads it under a row lock, so a second write waits for the first to commit
 * and then counts on from the value the first wrote, never from the one it would have read from
 * its own statement's snapshot.
 *
 * <p>The race is forced, not hoped for: the first increment is held open in its transaction
 * while the second is started, and is let go only once the second is observed waiting on a lock
 * in {@code sys.dm_exec_requests}.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class UserCounterSerializationIntegrationTests {

    /** A request on this database waiting for a lock. */
    private static final String WAITING = """
            SELECT count(*) FROM sys.dm_exec_requests
             WHERE database_id = DB_ID() AND wait_type LIKE 'LCK[_]M[_]%'""";

    @Autowired
    private UserCounterService counters;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private UUID created;

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        threads.shutdownNow();
        if (created != null) {
            ScimTestRows.deleteUnreserved(jdbc, created);
        }
    }

    @Test
    void anIncrementWaitingOnAnotherCountsOnFromTheValueTheOtherWrote() throws Exception {
        ScimUser user = new TransactionTemplate(transactionManager).execute(status -> users.create(
                ScimIdentities.user("counter-race-" + UUID.randomUUID().toString().substring(0, 8))));
        created = user.id();
        String userName = user.profile().userName();
        counters.increment(userName);

        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> first = threads.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    counters.increment(userName);
                    counted.countDown();
                    awaitQuietly(release);
                }));
        assertThat(counted.await(30, TimeUnit.SECONDS)).isTrue();
        Future<Long> second = threads.submit(() -> counters.increment(userName));
        try {
            awaitAWaitingRequest();
        } finally {
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
        }

        assertThat(second.get(30, TimeUnit.SECONDS))
                .as("the second increment counted on from the first's 2")
                .isEqualTo(3L);
        assertThat(counters.getCount(userName)).isEqualTo(3L);
    }

    private void awaitAWaitingRequest() throws InterruptedException {
        JdbcTemplate observer = logins.superuser().jdbc();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = observer.queryForObject(WAITING, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the second increment never waited on the first's lock");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
