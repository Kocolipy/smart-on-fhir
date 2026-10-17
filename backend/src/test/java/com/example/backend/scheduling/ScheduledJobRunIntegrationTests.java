package com.example.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.database.ConnectionQualifiers;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scheduling.domain.ScheduledJob;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled-job run module against real SQL Server and the real lock rows (ADR 0005): the
 * lock is held for the whole of a run's body — including across a transaction the body commits
 * of its own, as a job that commits per batch would — and released when the run ends, however
 * it ends. That includes a transaction committed on the retention pool, the audit retention
 * job's own data source and login, which is where its per-batch deletes commit.
 *
 * <p>The bodies here are the test's own, not a job's, so the claim is the module's and holds for
 * any job handed to it. A second run is started only once the first is inside its body, and the
 * first is held there until the second has answered.
 *
 * <p>The annotations match {@code EcsLogFormatTests}', so the two share one cached context and
 * one SQL Server container.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScheduledJobRunIntegrationTests {

    private static final String INSERT_EVENT = """
            INSERT INTO audit_events
                (id, occurred_at, operation, outcome, resource_type, status_class)
                VALUES (?, ?, 'LOGOUT', 'SUCCESS', 'User', 'ok')""";

    private static final String COUNT_EVENT = "SELECT count(*) FROM audit_events WHERE id = ?";

    private static final String DELETE_EVENT = "DELETE FROM audit_events WHERE id = ?";

    /** Any instant does: the batch deletes the event by id, not by age. */
    private static final Instant OCCURRED_AT = Instant.parse("2000-01-01T00:00:00Z");

    @Autowired
    private ScheduledJobRunService runs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    /** The retention pool's transaction manager, the one the retention job's deletes commit on. */
    @Autowired
    @Qualifier(ConnectionQualifiers.AUDIT_RETENTION_TRANSACTIONS)
    private PlatformTransactionManager retentionTransactions;

    /**
     * The retention pool's template: the retention login, the one login that may delete. The
     * pool's own bean rather than {@code DatabaseLogins.retention()}, whose connections sit
     * outside every pool: what is proven is a commit on the pool's own transaction manager.
     */
    @Autowired
    @Qualifier(ConnectionQualifiers.AUDIT_RETENTION_JDBC)
    private JdbcTemplate retentionJdbc;

    @Autowired
    private DatabaseLogins logins;

    /**
     * Two instances racing for one job's lock, observed in the server's own lock view: while the
     * first run is in its body, its job row carries exactly one lock — the first run's update
     * lock, granted — and the second run reads past it and skips at once, leaving no lock and no
     * waiter of its own behind ({@code READPAST}, ADR 0005 and ADR 0013's V3).
     */
    @Test
    void ofTwoRacingInstancesOneHoldsTheJobRowAndTheOtherReadsPastItWithoutWaiting()
            throws Exception {
        List<String> whileSkipping = new ArrayList<>();
        Optional<String> second = whileARunIsInsideItsBody(ScheduledJob.DORMANCY, () -> { },
                () -> {
                    whileSkipping.addAll(locksOnTheJobRows());
                    Optional<String> skipped =
                            runs.run(ScheduledJob.DORMANCY, () -> "the second run's work");
                    whileSkipping.addAll(locksOnTheJobRows());
                    return skipped;
                });

        assertThat(second).isEmpty();
        assertThat(whileSkipping).as("before and after the second run tried the lock")
                .containsExactly("U GRANT", "U GRANT");
    }

    @Test
    void aSecondRunOfTheSameJobSkipsWhileTheFirstRunsBodyIsInProgress() throws Exception {
        Optional<String> second = whileARunIsInsideItsBody(ScheduledJob.DORMANCY, () -> { },
                () -> runs.run(ScheduledJob.DORMANCY, () -> "the second run's work"));

        assertThat(second).isEmpty();
    }

    /**
     * A body that commits a transaction of its own — one per batch, say — commits only that:
     * the lock is the run's, in the run's transaction, and still held after the batch.
     */
    @Test
    void aTransactionTheBodyCommitsOfItsOwnDoesNotReleaseTheLock() throws Exception {
        TransactionTemplate batch = new TransactionTemplate(transactionManager);
        batch.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Optional<String> second = whileARunIsInsideItsBody(ScheduledJob.DORMANCY,
                () -> batch.executeWithoutResult(status -> jdbc.queryForObject(
                        "SELECT 1", Integer.class)),
                () -> runs.run(ScheduledJob.DORMANCY, () -> "the second run's work"));

        assertThat(second).isEmpty();
    }

    /**
     * A batch the retention job commits on the retention pool — another data source, as
     * another login — commits only that batch: its delete is visible to another connection
     * while the run is still in its body, and a second run of the job still skips. One
     * transaction per batch rests on this.
     */
    @Test
    void aBatchTheBodyCommitsOnTheRetentionPoolDoesNotReleaseTheLock() throws Exception {
        UUID event = UUID.randomUUID();
        jdbc.update(INSERT_EVENT, event, OCCURRED_AT.atOffset(ZoneOffset.UTC));
        TransactionTemplate batch = new TransactionTemplate(retentionTransactions);

        Optional<String> second = whileARunIsInsideItsBody(ScheduledJob.AUDIT_RETENTION,
                () -> batch.executeWithoutResult(status ->
                        retentionJdbc.update(DELETE_EVENT, event)),
                () -> {
                    assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, event))
                            .as("the batch committed: another connection no longer sees the event")
                            .isZero();
                    return runs.run(ScheduledJob.AUDIT_RETENTION, () -> "the second run's work");
                });

        assertThat(second).isEmpty();
    }

    @Test
    void anotherJobRunsWhileThisJobsRunIsInProgress() throws Exception {
        Optional<String> other = whileARunIsInsideItsBody(ScheduledJob.DORMANCY, () -> { },
                () -> runs.run(ScheduledJob.AUDIT_RETENTION, () -> "the other job's work"));

        assertThat(other).contains("the other job's work");
    }

    @Test
    void theNextRunTakesTheLockOnceTheRunHoldingItHasEnded() throws Exception {
        whileARunIsInsideItsBody(ScheduledJob.DORMANCY, () -> { }, Optional::empty);

        assertThat(runs.run(ScheduledJob.DORMANCY, () -> "the next run's work"))
                .contains("the next run's work");
    }

    @Test
    void aRunWhoseBodyThrowsReleasesTheLockForTheNext() {
        assertThatThrownBy(() -> runs.run(ScheduledJob.DORMANCY, () -> {
            throw new IllegalStateException("the run failed");
        })).hasMessage("the run failed");

        assertThat(runs.run(ScheduledJob.DORMANCY, () -> "the next run's work"))
                .contains("the next run's work");
    }

    /**
     * The job rows are the migration's, and the service's grants let it lock a row and nothing
     * else: a locking read ({@code UPDLOCK}) needs only {@code SELECT}, so the runtime role holds
     * no {@code UPDATE} and cannot rewrite the row a job serializes on. Every test above takes the
     * lock as that same role, so they hold that {@code SELECT} is enough.
     */
    @Test
    void theServiceCannotRewriteAJobsRow() {
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE scheduled_job_locks SET job_name = job_name WHERE job_name = ?",
                        ScheduledJob.DORMANCY.jobName()))
                .rootCause()
                .asInstanceOf(InstanceOfAssertFactories.throwable(SQLException.class))
                .extracting(SQLException::getErrorCode)
                .as("error 229, a permission denied")
                .isEqualTo(229);
    }

    /**
     * Starts a run of {@code job} on another thread whose body does {@code firstBodyWork} and
     * then waits; once it is waiting, does {@code meanwhile} here and lets the first run finish.
     * Fails unless the first run took the lock and returned its body's result.
     */
    private <T> T whileARunIsInsideItsBody(
            ScheduledJob job, Runnable firstBodyWork, Supplier<T> meanwhile) throws Exception {
        CountDownLatch insideBody = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService firstRunner = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> first = firstRunner.submit(() -> runs.run(job, () -> {
                firstBodyWork.run();
                insideBody.countDown();
                awaitQuietly(release);
                return "the first run's work";
            }));
            assertThat(insideBody.await(30, TimeUnit.SECONDS))
                    .as("the first run reached its body").isTrue();
            T result;
            try {
                result = meanwhile.get();
            } finally {
                release.countDown();
            }
            assertThat(first.get(60, TimeUnit.SECONDS))
                    .as("the first run held the lock").contains("the first run's work");
            return result;
        } finally {
            release.countDown();
            firstRunner.shutdownNow();
        }
    }

    /**
     * The row locks on {@code scheduled_job_locks} in this database, as the server lists them:
     * mode and status. Read as the server's administrator, since another session's locks are
     * visible only with {@code VIEW SERVER STATE}.
     */
    private List<String> locksOnTheJobRows() {
        return logins.superuser().jdbc().queryForList("""
                SELECT l.request_mode + ' ' + l.request_status
                  FROM sys.dm_tran_locks l
                  JOIN sys.partitions p ON p.hobt_id = l.resource_associated_entity_id
                 WHERE l.resource_database_id = DB_ID() AND l.resource_type = 'KEY'
                   AND p.object_id = OBJECT_ID('scheduled_job_locks')""", String.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
