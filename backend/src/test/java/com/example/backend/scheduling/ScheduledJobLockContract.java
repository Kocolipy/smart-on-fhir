package com.example.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScheduledJobLock} does, whatever holds the locks: the SQL Server adapter, and
 * the in-memory lock the scheduled jobs' unit tests run against. Each runs it.
 *
 * <p>A lock is held for the rest of the transaction that took it, so every ask is made in one
 * ({@link #inTransaction}); and a lock held elsewhere is held by another run's transaction
 * ({@link #heldByAnotherRun}), which for SQL Server is a real one on another connection, still
 * open while the case asks.
 *
 * <p>Asked outside any transaction the two differ, and the contract leaves that out: the in-memory
 * lock refuses, so a unit test finds a run that would take the lock in autocommit, while the
 * adapter takes it and releases it at once. The one caller always opens a transaction first
 * ({@code ScheduledJobRunService}, held there by {@code ArchitectureTest}).
 */
public abstract class ScheduledJobLockContract {

    /** The lock port under test. */
    protected abstract ScheduledJobLock lock();

    /** Runs {@code work} in a transaction of its own, ended once {@code work} returns. */
    protected abstract <T> T inTransaction(Supplier<T> work);

    /** Another run holds {@code job}'s lock, in a transaction it keeps open until the case ends. */
    protected abstract void heldByAnotherRun(ScheduledJob job);

    @Test
    void a_lock_no_run_holds_is_taken() {
        assertThat(inTransaction(() -> lock().tryAcquire(ScheduledJob.DORMANCY))).isTrue();
    }

    /** Exclusive: while another run holds a job's lock, this run does not get it. */
    @Test
    void a_lock_another_run_holds_is_not_taken() {
        heldByAnotherRun(ScheduledJob.DORMANCY);

        assertThat(inTransaction(() -> lock().tryAcquire(ScheduledJob.DORMANCY))).isFalse();
    }

    /** Per job: one job's lock held elsewhere says nothing about another's. */
    @Test
    void another_jobs_lock_is_taken_while_one_job_is_held_elsewhere() {
        heldByAnotherRun(ScheduledJob.DORMANCY);

        assertThat(inTransaction(() -> lock().tryAcquire(ScheduledJob.AUDIT_RETENTION))).isTrue();
    }

    /** The run holding a lock is not excluded by it: asked again, it still holds it. */
    @Test
    void the_run_holding_a_lock_takes_it_again() {
        assertThat(inTransaction(() -> lock().tryAcquire(ScheduledJob.AUDIT_RETENTION)
                && lock().tryAcquire(ScheduledJob.AUDIT_RETENTION))).isTrue();
    }

    /** A lock lasts as long as the transaction that took it, so the next run takes it again. */
    @Test
    void a_lock_is_taken_again_once_the_transaction_that_took_it_has_ended() {
        inTransaction(() -> lock().tryAcquire(ScheduledJob.DORMANCY));

        assertThat(inTransaction(() -> lock().tryAcquire(ScheduledJob.DORMANCY))).isTrue();
    }
}
