package com.example.backend.audit.application;

import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>A run takes the job's own lock ({@link ScheduledJob#AUDIT_RETENTION}) first, so two
 * instances on the same cron do not both delete: the second finds the lock held, does
 * nothing, and reports it {@linkplain AuditRetentionRun#skipped skipped}, which
 * {@code ScheduledJobMetrics.instrumentLocked} logs as the run's {@code lock-held} end. The
 * lock is taken before the adapter assumes the retention role, as the application's own
 * role, which is the one granted it.
 *
 * <p>{@code @Transactional} is load-bearing rather than incidental. The lock is held for
 * the rest of the transaction, and the adapter assumes the retention database role with
 * {@code SET LOCAL ROLE}, which needs a transaction to be local to; without one the delete
 * would run as the application's own role and be refused.
 */
@Service
public class AuditRetentionService {

    /** The operation every record this job emits is classified as, its schedule included. */
    public static final Operation OPERATION = Operation.AUDIT_RETENTION;

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

    private final AuditEventRetention retention;
    private final AuditRetentionPolicy policy;
    private final ScheduledJobLock lock;
    private final Clock clock;

    public AuditRetentionService(
            AuditEventRetention retention,
            AuditRetentionPolicy policy,
            ScheduledJobLock lock,
            Clock clock) {
        this.retention = retention;
        this.policy = policy;
        this.lock = lock;
        this.clock = clock;
    }

    /**
     * Removes every event recorded longer ago than the retention window, or skips when
     * another run of this job holds its lock.
     *
     * @return whether the run skipped, and how many rows it removed
     */
    @Transactional
    public AuditRetentionRun deleteAgedOutEvents() {
        if (!lock.tryAcquire(ScheduledJob.AUDIT_RETENTION)) {
            return AuditRetentionRun.skippedRun();
        }
        Instant cutoff = clock.instant().minus(policy.period());
        long deleted = retention.deleteOccurredBefore(cutoff);
        LogEvent.jobSummary(log, OPERATION)
                .addKeyValue(AuditLogFields.RETENTION_PERIOD, policy.period().toString())
                .addKeyValue(AuditLogFields.RETENTION_DELETED_ROWS, deleted)
                .log();
        return new AuditRetentionRun(false, deleted);
    }
}
