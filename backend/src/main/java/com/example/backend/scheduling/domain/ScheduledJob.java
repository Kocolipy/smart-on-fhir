package com.example.backend.scheduling.domain;

import com.example.backend.observability.LogEvent.Operation;

/**
 * The scheduled jobs that must never overlap themselves: each job's one identity, spelled once.
 *
 * <p>A job's {@linkplain #jobName name} is its lock row in {@code scheduled_job_locks}, the
 * {@code job} tag of its run metrics and its {@code batch.job.name}; its {@linkplain #operation
 * Operation} classifies the records its runs emit. Both are read from here by the module that
 * runs the job ({@code scheduling.application.ScheduledJobRunService}) and by the schedule config
 * that registers it, so the lock, the metrics and the records cannot name the job differently.
 *
 * <p>A closed set because the lock rows are: every member here has a row in
 * {@code scheduled_job_locks}, written by the migration that introduced it, and a job the
 * schema does not know cannot be serialized at all. The names are fixed by that migration and
 * by the alert rules in {@code ops/prometheus/alerts.yaml}, which select on the {@code job} tag;
 * renaming a member's name is a change to both.
 *
 * <p>The module is shared rather than any one job's: the dormancy job belongs to {@code auth} and
 * the retention job to {@code audit}, and {@code audit} may depend on no business module. It
 * depends on {@code observability} for the {@link Operation} vocabulary, which every module
 * logs through. See {@code /docs/adr/0005-serialize-scheduled-jobs-on-per-job-lock-rows.md}.
 */
public enum ScheduledJob {

    /** Locks Users past the dormancy lockout window and removes the Group memberships of those
     *  past the revocation window. */
    DORMANCY("dormancy", Operation.DORMANCY),

    /** Deletes audit events older than the retention period. */
    AUDIT_RETENTION("audit-retention", Operation.AUDIT_RETENTION);

    private final String jobName;

    private final Operation operation;

    ScheduledJob(String jobName, Operation operation) {
        this.jobName = jobName;
        this.operation = operation;
    }

    /**
     * The name the job is known by everywhere: its lock row, the {@code job} tag of its run
     * metrics, and its {@code batch.job.name}.
     */
    public String jobName() {
        return jobName;
    }

    /** What the job's own records — its schedule, start, end and summary — are classified as. */
    public Operation operation() {
        return operation;
    }
}
