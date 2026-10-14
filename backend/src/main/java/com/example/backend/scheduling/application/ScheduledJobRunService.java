package com.example.backend.scheduling.application;

import com.example.backend.database.ConnectionQualifiers;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled-job run module: the one place a serialized job's run opens the transaction its
 * lock lives in, tries the lock, reports a skip, and hands the job its body (ADR 0005).
 *
 * <p>ADR 0005 holds only while three things line up: the transaction the lock is taken in, the
 * data source the lock row is on, and how long that transaction lasts. This module fixes all
 * three, so a job body cannot misalign them:
 *
 * <ul>
 *   <li><strong>The transaction is the run's own.</strong> It is opened here, through a
 *       {@link TransactionTemplate} with {@code PROPAGATION_REQUIRES_NEW}, rather than by a
 *       {@code @Transactional} on the job: a caller's transaction is suspended, never joined, so
 *       the lock is released by the end of this run and by nothing else, and a run has
 *       committed by the time it returns — which is what lets {@code ScheduledJobMetrics} count
 *       a returned run as committed work.
 *   <li><strong>It is on the primary data source.</strong> The template is built on the
 *       primary transaction manager, named rather than resolved by type, because only the
 *       application login holds the grants on {@code scheduled_job_locks}. A second transaction
 *       manager added for a job's own work cannot become the lock's by being the one a
 *       {@code @Transactional} happens to resolve to; if the primary one is ever renamed, the
 *       context fails to start rather than silently locking elsewhere.
 *   <li><strong>It lasts the whole run.</strong> The body runs inside it, so the lock is held
 *       until the body has returned or thrown. A transaction the body opens of its own — on
 *       another data source's transaction manager, or on the primary one with
 *       {@code PROPAGATION_REQUIRES_NEW} — opens, commits and ends inside the run's and releases
 *       nothing. A per-batch commit on the primary data source needs {@code REQUIRES_NEW}: a
 *       per-batch {@code @Transactional} with the default {@code REQUIRED} propagation joins the
 *       run's transaction instead, so nothing commits until the run does.
 * </ul>
 *
 * <p>A body that changes data on the primary data source joins the run's transaction by
 * default propagation, as the dormancy job's writes do: one transaction per run, rolled back
 * whole by a body that throws (ADR 0004's fail-closed half).
 *
 * <p>A job body never takes the lock itself: only this module calls {@link ScheduledJobLock}
 * ({@code ArchitectureTest.only_the_scheduled_job_run_takes_a_job_lock}).
 */
@Service
public class ScheduledJobRunService {

    private final ScheduledJobLock lock;

    /** Opens each run's own transaction on the primary transaction manager. */
    private final TransactionTemplate runTransaction;

    /**
     * @param primary the primary data source's transaction manager — Boot's
     *                {@link ConnectionQualifiers#MAIN_TRANSACTIONS}, the one the lock row's grants are on
     */
    public ScheduledJobRunService(
            ScheduledJobLock lock,
            @Qualifier(ConnectionQualifiers.MAIN_TRANSACTIONS) PlatformTransactionManager primary) {
        this.lock = lock;
        this.runTransaction = new TransactionTemplate(primary);
        this.runTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs {@code body} as one run of {@code job}: inside a transaction of the run's own, once
     * this run holds the job's lock, which it keeps until the body has returned and the
     * transaction ended. Commits what the body did when it returns, and rolls it back and
     * rethrows when it throws.
     *
     * @param body the run's work; must not return {@code null}
     * @return what the body returned, or empty when another run of the job holds its lock, in
     *         which case the body did not run
     */
    public <R> Optional<R> run(ScheduledJob job, Supplier<R> body) {
        return Objects.requireNonNull(runTransaction.execute(status -> lock.tryAcquire(job)
                ? Optional.of(body.get())
                : Optional.<R>empty()));
    }
}
