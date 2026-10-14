package com.example.backend.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scheduling.InMemoryScheduledJobLock;
import com.example.backend.scheduling.RecordingTransactionManager;
import com.example.backend.scheduling.domain.ScheduledJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The module that runs a scheduled job (ADR 0005): the lock is taken in a transaction the module
 * opens, a run that finds it held skips without running its body, and a run that takes it runs
 * the body inside that same transaction, which a throwing body rolls back.
 *
 * <p>That the lock outlives the body against a real database is
 * {@code ScheduledJobRunIntegrationTests}'.
 */
class ScheduledJobRunServiceTests {

    private final List<String> steps = new ArrayList<>();

    private final RecordingTransactionManager transactions = new RecordingTransactionManager(steps);

    private final InMemoryScheduledJobLock lock = new InMemoryScheduledJobLock();

    private final ScheduledJobRunService runs = new ScheduledJobRunService(lock, transactions);

    @Test
    void aRunThatTakesTheLockReturnsWhatTheBodyReturned() {
        assertThat(runs.run(ScheduledJob.DORMANCY, () -> "did the work"))
                .contains("did the work");
    }

    @Test
    void aRunThatFindsTheLockHeldSkipsWithoutRunningTheBody() {
        lock.holdElsewhere(ScheduledJob.DORMANCY);

        Optional<String> run = runs.run(ScheduledJob.DORMANCY, () -> {
            steps.add("body");
            return "did the work";
        });

        assertThat(run).isEmpty();
        assertThat(steps).containsExactly("begin", "commit");
    }

    /** The lock asked for is the run's own job's; another job's being held does not stop it. */
    @Test
    void aRunAsksForItsOwnJobsLockOnly() {
        lock.holdElsewhere(ScheduledJob.DORMANCY);

        runs.run(ScheduledJob.AUDIT_RETENTION, () -> "did the work");

        assertThat(lock.attempts()).containsExactly(ScheduledJob.AUDIT_RETENTION);
    }

    /**
     * The body runs after the lock was taken and before the transaction that holds it ends, so
     * the lock is held for the whole of the body; the in-memory lock refuses a caller outside a
     * transaction, as the SQL one has nothing to hold the lock for there.
     */
    @Test
    void theBodyRunsInsideTheTransactionTheLockWasTakenIn() {
        runs.run(ScheduledJob.DORMANCY, () -> {
            steps.add("body");
            return "did the work";
        });

        assertThat(steps).containsExactly("begin", "body", "commit");
    }

    @Test
    void aBodyThatThrowsRollsTheRunBackAndTheFailurePropagates() {
        IllegalStateException failure = new IllegalStateException("the audit append failed");

        assertThatThrownBy(() -> runs.run(ScheduledJob.DORMANCY, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(steps).containsExactly("begin", "rollback");
    }

    /**
     * The run's transaction is its own even when its caller already has one: the caller's is
     * suspended, the lock lives and ends in the run's, and the run has committed by the time it
     * returns — so whatever counts a returned run counts committed work.
     */
    @Test
    void aRunCalledInsideAnotherTransactionStillOwnsItsOwn() {
        new TransactionTemplate(transactions).executeWithoutResult(callers -> {
            steps.add("caller's work");
            runs.run(ScheduledJob.DORMANCY, () -> {
                steps.add("body");
                return "did the work";
            });
            steps.add("run returned");
        });

        assertThat(steps).containsExactly(
                "begin", "caller's work",
                "suspend", "begin", "body", "commit", "resume",
                "run returned", "commit");
    }
}
