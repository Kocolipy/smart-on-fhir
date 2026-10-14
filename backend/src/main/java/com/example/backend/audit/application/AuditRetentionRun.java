package com.example.backend.audit.application;

import com.example.backend.observability.JobRecord;
import com.example.backend.observability.SkippableJobRun;
import java.util.List;

/**
 * What one retention run did: skipped because another run held the job's lock, or ran and
 * removed this many events in this many batches.
 *
 * @param skipped whether another run of the job held its lock, so this one did nothing
 * @param deleted how many events this run removed; zero when it skipped or nothing had aged out
 * @param batches how many delete statements the run committed; zero when it skipped
 */
public record AuditRetentionRun(boolean skipped, long deleted, long batches)
        implements SkippableJobRun {

    /** The kind of record the run deletes, as its {@code job-end}'s {@code record} names it. */
    public static final String RECORD_TYPE = "audit_event";

    /** A run that found the job's lock held and did nothing. */
    static AuditRetentionRun skippedRun() {
        return new AuditRetentionRun(true, 0, 0);
    }

    /**
     * What a failed run had committed before its delete did: nothing. The job's schedule states
     * it for the failed {@code job-end}; a run whose delete committed and that failed afterwards
     * reports the committed batch in its place ({@code JobProgress}).
     */
    public static List<JobRecord> committedByAFailedRun() {
        return new AuditRetentionRun(false, 0, 0).records();
    }

    /** The run's {@code record}: the audit events it deleted, and in how many batches. */
    @Override
    public List<JobRecord> records() {
        return List.of(JobRecord.of(RECORD_TYPE, "deleted", deleted, "batches", batches));
    }
}
