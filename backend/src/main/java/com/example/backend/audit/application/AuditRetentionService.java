package com.example.backend.audit.application;

import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.observability.LogEvent;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scheduling.domain.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enforces the retention window: one pass that removes every event older than it.
 *
 * <p>Each run reports what it did — how many rows it removed — because a retention
 * job is otherwise the one thing in the service whose correct behaviour and total
 * failure look identical from outside. A run that deletes nothing is logged too:
 * "nothing had aged out" and "the job has not run for a month" are different facts
 * and an operator needs to be able to tell them apart. The run's start, its end, how
 * long it took and whether it failed are {@code ScheduledJobMetrics}' to log, around
 * this record; scheduled through it, this record carries the run's
 * {@code batch.job.run.id}.
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
 */
@Service
public class AuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

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

    /** The run's body, inside the run's transaction and under the job's lock. */
    private AuditRetentionRun deleteHoldingTheLock() {
        Instant cutoff = clock.instant().minus(policy.period());
        long deleted = retention.deleteOccurredBefore(cutoff);
        LogEvent.jobSummary(log, ScheduledJob.AUDIT_RETENTION.operation())
                .addKeyValue(AuditLogFields.RETENTION_PERIOD, policy.period().toString())
                .addKeyValue(AuditLogFields.RETENTION_DELETED_ROWS, deleted)
                .log();
        return new AuditRetentionRun(false, deleted);
    }
}
