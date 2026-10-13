package com.example.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scheduling.domain.ScheduledJob;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled-job run module against real Postgres and the real lock rows (ADR 0005): the
 * lock is held for the whole of a run's body — including across a transaction the body commits
 * of its own, as a job that commits per batch would — and released when the run ends, however
 * it ends.
 *
 * <p>The bodies here are the test's own, not a job's, so the claim is the module's and holds for
 * any job handed to it. A second run is started only once the first is inside its body, and the
 * first is held there until the second has answered.
 *
 * <p>The annotations match {@code EcsLogFormatTests}', so the two share one cached context and
 * one Postgres container.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScheduledJobRunIntegrationTests {

    @Autowired
    private ScheduledJobRunService runs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

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

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
