package com.example.backend.audit.application;

import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.observability.JobProgress;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scheduling.domain.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Enforces the retention window: one pass that removes every event older than it, in one
 * delete statement — one batch, committed in a transaction of its own on the retention pool.
 *
 * <p>Each run reports what it did — how many rows it removed, in how many batches
 * ({@link AuditRetentionRun}) — because a retention job is otherwise the one thing in the
 * service whose correct behaviour and total failure look identical from outside. A run that
 * deletes nothing reports too: "nothing had aged out" and "the job has not run for a month" are
 * different facts and an operator needs to be able to tell them apart. The report is written
 * once, by {@code ScheduledJobMetrics}, as the run's one summary line — its {@code job-end},
 * with the outcome, {@code batch.job.status}, the {@code record} and the duration — so this
 * service writes no record of its own, and no line per batch (ADR 0003, "Scheduled jobs").
 *
 * <h2>Serialization</h2>
 *
 * <p>A run goes through the module that runs a scheduled job ({@link ScheduledJobRunService}),
 * which takes the job's own lock ({@link ScheduledJob#AUDIT_RETENTION}) first, so two
 * instances on the same cron do not both delete: the second finds the lock held, does
 * nothing, and reports it {@linkplain AuditRetentionRun#skipped skipped}, which
 * {@code ScheduledJobMetrics.instrumentLocked} logs as the run's {@code lock-held} end. The
 * lock is on the main pool, as the app login, which is the one granted it.
 *
 * <p>The delete runs on another pool, as the retention login — the one principal the audit
 * table lets delete — in a transaction of the retention pool's own, which the adapter opens. It
 * commits inside the run's transaction on the main pool and releases nothing: the lock is the
 * module's, held for the whole run whatever transactions the body opens of its own (ADR 0005).
 *
 * <p>So the delete has committed before the run's own transaction does, and a run can fail after
 * it — when that transaction's commit fails. The run reports the committed batch as it commits
 * ({@link JobProgress#committed}), so the failed run's line counts it rather than the nothing a
 * run whose delete failed committed ({@link AuditRetentionRun#committedByAFailedRun}).
 */
@Service
public class AuditRetentionService {

    private final AuditEventRetention retention;
    private final AuditRetentionPolicy policy;
    private final ScheduledJobRunService runs;
    private final Clock clock;

    public AuditRetentionService(
            AuditEventRetention retention,
            AuditRetentionPolicy policy,
            ScheduledJobRunService runs,
            Clock clock) {
        this.retention = retention;
        this.policy = policy;
        this.runs = runs;
        this.clock = clock;
    }

    /**
     * Removes every event recorded longer ago than the retention window, or skips when
     * another run of this job holds its lock.
     *
     * @return whether the run skipped, and how many rows it removed
     */
    public AuditRetentionRun deleteAgedOutEvents() {
        return runs.run(ScheduledJob.AUDIT_RETENTION, this::deleteHoldingTheLock)
                .orElseGet(AuditRetentionRun::skippedRun);
    }

    /**
     * The run's body, inside the run's transaction and under the job's lock: one delete
     * statement, which is the run's one batch, reported committed once it returns.
     */
    private AuditRetentionRun deleteHoldingTheLock() {
        Instant cutoff = clock.instant().minus(policy.period());
        long deleted = retention.deleteOccurredBefore(cutoff);
        AuditRetentionRun run = new AuditRetentionRun(false, deleted, 1);
        JobProgress.committed(run.records());
        return run;
    }
}
