package com.example.backend.auth.infrastructure.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.backend.scheduling.RecordingTransactionManager;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The adapter against Spring's real synchronization registry, driven without a
 * database: the registry is thread-bound, so a test can open synchronization,
 * hand work over, and trigger the commit or the rollback itself.
 *
 * <p>Worth testing directly because the whole value of the port is an ordering,
 * and an adapter that ran the work inline would satisfy every caller's compile
 * and silently restore the bug it exists to prevent.
 */
class AfterCommitAdapterTests {

    private final AfterCommitAdapter adapter = new AfterCommitAdapter();

    private final List<String> done = new ArrayList<>();

    @AfterEach
    void clearAnyTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void holdsTheWorkUntilTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.run(() -> done.add("revoked"));

        assertThat(done).isEmpty();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(done).containsExactly("revoked");
    }

    /** A rollback is the case the ordering exists for: the work must never run. */
    @Test
    void neverRunsTheWorkWhenTheTransactionDoesNotCommit() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.run(() -> done.add("revoked"));
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(done).isEmpty();
    }

    /**
     * With no transaction bound there is no commit to wait for and no rollback that
     * could contradict the work, so deferring it would only mean never running it.
     */
    @Test
    void runsTheWorkImmediatelyWithNoTransaction() {
        adapter.run(() -> done.add("revoked"));

        assertThat(done).containsExactly("revoked");
    }

    @Test
    void keepsTheOrderWorkWasHandedOverIn() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.run(() -> done.add("first"));
        adapter.run(() -> done.add("second"));
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(done).containsExactly("first", "second");
    }

    /**
     * A transaction may already carry synchronizations of its own — the persistence layer's, or
     * any other — and the work still waits for the commit and runs once it does.
     */
    @Test
    void runsTheWorkBesideAnotherSynchronizationOfTheTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                done.add("other");
            }
        });

        adapter.run(() -> done.add("revoked"));
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(done).containsExactly("other", "revoked");
    }

    // ---- a failing piece of work -------------------------------------------------------------

    private final IllegalStateException storeDown = new IllegalStateException("store down");

    /**
     * Spring runs a transaction's synchronizations one after another and stops at the first that
     * throws, so work handed over as one synchronization each would leave everything after a
     * failure unrun: one member's session store timing out would keep every later member signed in.
     */
    @Test
    void aFailingPieceOfWorkDoesNotStopTheWorkHandedOverAfterIt() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.run(() -> {
            throw storeDown;
        });
        adapter.run(() -> done.add("second"));
        catchThrowable(TransactionSynchronizationUtils::triggerAfterCommit);

        assertThat(done).containsExactly("second");
    }

    /** The caller still learns of the failure — the first one — once every piece has run. */
    @Test
    void theFirstFailureSurfacesOnceEveryPieceHasRun() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.run(() -> {
            throw storeDown;
        });
        adapter.run(() -> done.add("second"));

        assertThat(catchThrowable(TransactionSynchronizationUtils::triggerAfterCommit))
                .isSameAs(storeDown);
    }

    /** A later failure is not lost: it rides on the first as a suppressed exception. */
    @Test
    void aLaterFailureIsCarriedOnTheFirst() {
        TransactionSynchronizationManager.initSynchronization();
        IllegalStateException alsoDown = new IllegalStateException("also down");

        adapter.run(() -> {
            throw storeDown;
        });
        adapter.run(() -> {
            throw alsoDown;
        });

        assertThat(catchThrowable(TransactionSynchronizationUtils::triggerAfterCommit)
                .getSuppressed()).containsExactly(alsoDown);
    }

    /**
     * Through a real transaction manager's commit, the path a request takes: the commit call
     * reports the first failure, and the work after it has run by then.
     */
    @Test
    void aCommitReportsTheFirstFailureAfterRunningTheWorkBehindIt() {
        TransactionTemplate transaction =
                new TransactionTemplate(new RecordingTransactionManager());

        Throwable reported = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            adapter.run(() -> {
                throw storeDown;
            });
            adapter.run(() -> done.add("second"));
        }));

        assertThat(List.of(reported, done)).containsExactly(storeDown, List.of("second"));
    }
}
