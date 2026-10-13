package com.example.backend.scheduling.domain;

/**
 * Keeps two runs of the same scheduled job from overlapping — on this instance or any other —
 * without making different jobs wait for each other.
 *
 * <p>A lock is per job, and held for the rest of the calling transaction: it is released by the
 * commit or the rollback that ends the run, so there is no release to forget and no lease to
 * outlive a crashed instance. A run that cannot take the lock does not wait for it; the other run
 * is already doing the same work, so the right answer is to skip.
 *
 * <p>Which transaction that is decides how long the lock is held, so it has one caller: the
 * scheduled-job run module ({@code scheduling.application.ScheduledJobRunService}), which takes it
 * in the run's own transaction on the primary data source and holds that open for the whole of
 * the job's body. A job body never takes it itself
 * ({@code ArchitectureTest.only_the_scheduled_job_run_takes_a_job_lock}).
 */
public interface ScheduledJobLock {

    /**
     * Takes this job's lock for the rest of the current transaction, or reports that another run
     * of the same job holds it.
     *
     * <p>Must be called inside a transaction; the lock has nothing to be held for otherwise.
     *
     * @return {@code true} when this run holds the lock, {@code false} when another run does
     */
    boolean tryAcquire(ScheduledJob job);
}
