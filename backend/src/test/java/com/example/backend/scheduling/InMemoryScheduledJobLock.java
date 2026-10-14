package com.example.backend.scheduling;

import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The job lock, in memory, for a unit test of a scheduled job.
 *
 * <p>A test says which jobs another run is holding; every other job's lock is free. Keyed by job
 * rather than a single flag, so a test can show that one job's lock being held says nothing
 * about the other's.
 *
 * <p>Like the SQL adapter, it has nothing to hold a lock for outside a transaction, and refuses
 * to be asked there: a test that reaches it with none open has found a run that would take the
 * lock in autocommit and release it at once.
 */
public final class InMemoryScheduledJobLock implements ScheduledJobLock {

    private final Set<ScheduledJob> heldElsewhere = EnumSet.noneOf(ScheduledJob.class);

    private final List<ScheduledJob> attempts = new ArrayList<>();

    /** Another run of this job holds its lock. */
    public void holdElsewhere(ScheduledJob job) {
        heldElsewhere.add(job);
    }

    /** Every job a lock was asked for, in order. */
    public List<ScheduledJob> attempts() {
        return List.copyOf(attempts);
    }

    @Override
    public boolean tryAcquire(ScheduledJob job) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The " + job.jobName() + " lock was asked for outside a transaction");
        }
        attempts.add(job);
        return !heldElsewhere.contains(job);
    }
}
