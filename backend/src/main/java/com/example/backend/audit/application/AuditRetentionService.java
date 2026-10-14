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
 * Enforces the retention window: one pass that removes every event older than it, in batches of
 * at most {@link AuditEventRetention#BATCH_SIZE}, each committed in a transaction of its own on
 * the retention pool. A batch stays below SQL Server's lock-escalation threshold, so it holds row
 * locks only and an audit append is never queued behind it; a long run is many short
 * transactions rather than one that holds the table (ADR 0015). Retention is idempotent: a run
 * that fails part-way keeps the batches it committed, and the next run deletes the rest.
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
 * <p>The batches run on another pool, as the retention login — the one principal the audit
 * table lets delete — each in a transaction of the retention pool's own, which the adapter opens.
 * Each commits inside the run's transaction on the main pool and releases nothing: the lock is
 * the module's, held for the whole run whatever transactions the body opens of its own (ADR
 * 0005), so a second instance started between two batches still finds it held and skips.
 *
 * <p>So the batches have committed before the run's own transaction does, and a run can fail
 * after any of them — in a later batch, or when that transaction's commit fails. The run reports
 * what it has committed after each batch ({@link JobProgress#committed}), so the failed run's
 * line counts it rather than the nothing a run whose first batch failed committed
 * ({@link AuditRetentionRun#committedByAFailedRun}).
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
     * The run's body, inside the run's transaction and under the job's lock: batch after batch,
     * each reported committed once it returns, until one comes back short of a whole batch —
     * nothing recorded before the cutoff is then left. The cutoff is taken once, so the run
     * deletes what had aged out when it started and does not chase what ages out while it runs.
     */
    private AuditRetentionRun deleteHoldingTheLock() {
        Instant cutoff = clock.instant().minus(policy.period());
        AuditRetentionRun run = new AuditRetentionRun(false, 0, 0);
        long batch;
        do {
            batch = retention.deleteBatchOccurredBefore(cutoff);
            run = new AuditRetentionRun(false, run.deleted() + batch, run.batches() + 1);
            JobProgress.committed(run.records());
        } while (batch >= AuditEventRetention.BATCH_SIZE);
        return run;
    }
}
