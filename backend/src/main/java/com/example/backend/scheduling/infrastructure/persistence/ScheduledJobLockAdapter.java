package com.example.backend.scheduling.infrastructure.persistence;

import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Serializes each scheduled job on its own row of {@code scheduled_job_locks}.
 *
 * <p>{@code WITH (UPDLOCK, ROWLOCK, READPAST)} is the whole mechanism (ADR 0005, ADR 0015). The
 * row's update lock is held until the calling transaction ends, so a second run of the same job —
 * in this process or another one against the same database — reads past the held row and learns
 * it is not the one to run, without waiting; spike V3 verified that {@code READPAST} skips a held
 * row under read-committed snapshot. A run of the other job locks a different row and never sees
 * this one.
 *
 * <p>JDBC rather than JPA: the row has no attributes worth mapping, and the statement is a lock,
 * not a read. It runs on the primary data source — the application login is the one granted the
 * table — and joins the transaction open there, which is always the run's own: the scheduled-job
 * run module ({@code ScheduledJobRunService}) opens it on the primary transaction manager and is
 * the adapter's only caller.
 *
 * <p>The one place the engine's lock syntax is written: a change of engine (ADR 0015) changes
 * this statement and nothing that calls it.
 */
@Repository
class ScheduledJobLockAdapter implements ScheduledJobLock {

    /** Whole statements as constants, never assembled; see the local Semgrep ruleset. */
    private static final String LOCK_JOB_ROW = """
            SELECT job_name FROM scheduled_job_locks WITH (UPDLOCK, ROWLOCK, READPAST)
             WHERE job_name = ?""";

    private static final String COUNT_JOB_ROW =
            "SELECT count(*) FROM scheduled_job_locks WHERE job_name = ?";

    private final JdbcTemplate jdbc;

    ScheduledJobLockAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the job's row, or reports it held.
     *
     * <p>{@code READPAST} returns nothing both for a row another run holds and for a row that
     * does not exist, and the two must not be confused: a missing row would otherwise read as
     * "someone else is running it" forever, and the job would silently never run. So an empty
     * result is followed by a plain read, which does not wait for the lock, and a missing row
     * fails the run loudly.
     */
    @Override
    public boolean tryAcquire(ScheduledJob job) {
        List<String> locked = jdbc.queryForList(LOCK_JOB_ROW, String.class, job.jobName());
        if (!locked.isEmpty()) {
            return true;
        }
        Integer rows = jdbc.queryForObject(COUNT_JOB_ROW, Integer.class, job.jobName());
        if (rows == null || rows == 0) {
            throw new IllegalStateException(
                    "No lock row exists for scheduled job " + job.jobName()
                            + "; the migration that creates it has not run.");
        }
        return false;
    }
}
