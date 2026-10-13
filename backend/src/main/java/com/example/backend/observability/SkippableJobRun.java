package com.example.backend.observability;

import java.util.Map;

/**
 * What one run of a job that serializes on a lock reports back to
 * {@link ScheduledJobMetrics#instrumentLocked}: whether it found another run of the same
 * job holding the lock, and so did no work.
 *
 * <p>The lock is taken inside the run, in the transaction the scheduled-job run module
 * ({@code scheduling.application.ScheduledJobRunService}) opens for it, so only the run knows
 * whether it did the work; this is how it says so, and the wrapper logs the run's end
 * accordingly.
 */
public interface SkippableJobRun {

    /** Whether another run of the same job held the lock, so this one did nothing. */
    boolean skipped();

    /**
     * What a run that did the work counted, keyed by log field — reported once, here, and
     * written from here both onto its {@code job-end} record, so the record that says the run
     * ended also says what it did, and onto any counter the job declared for the field
     * ({@link ScheduledJobSpec#countedAs}). Empty by default, and ignored for a skipped run.
     */
    default Map<String, Long> counts() {
        return Map.of();
    }
}
