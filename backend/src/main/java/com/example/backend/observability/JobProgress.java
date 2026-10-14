package com.example.backend.observability;

import java.util.List;

/**
 * What a scheduled run has committed so far, reported by its body as each of its own
 * transactions commits, so a run that fails afterwards still counts it on its failed
 * {@code job-end} (ADR 0003, "Scheduled jobs").
 *
 * <p>A run's body runs inside the run's lock transaction, but what it writes does not always
 * commit with that transaction: the audit retention job deletes on a pool of its own, in a
 * transaction of that pool's transaction manager, which commits before the run's does. When the
 * run then fails — its lock transaction's commit included — the deletes stand, and the failed
 * line must say so rather than state the schedule's nothing-committed
 * ({@link ScheduledJobSpec#committedOnFailure}). A run that returns reports its whole work in
 * its result ({@link SkippableJobRun#records}); this is only for the run that does not return.
 *
 * <p>The report is the run's thread's, held for the run's lifetime by
 * {@link ScheduledJobMetrics}, which opens it before the body runs and drops it when the run
 * ends however it ended, so nothing one run reported reaches the next. Reported outside any
 * scheduled run, it is nobody's and is dropped.
 */
public final class JobProgress {

    /** The current run's report, on the thread the run's body runs on; absent between runs. */
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    private JobProgress() {
    }

    /**
     * Reports what the current run has committed so far — all of it, replacing what it reported
     * before, as each element of its {@code record} array would be written.
     */
    public static void committed(List<JobRecord> records) {
        Run run = CURRENT.get();
        if (run != null) {
            run.committed = List.copyOf(records);
        }
    }

    /**
     * Opens the current thread's run, which reports {@code nothingCommitted} until its body says
     * otherwise.
     */
    static Run start(List<JobRecord> nothingCommitted) {
        Run run = new Run(nothingCommitted);
        CURRENT.set(run);
        return run;
    }

    /** One run's report, open from {@link #start} until it is closed. */
    static final class Run implements AutoCloseable {

        private List<JobRecord> committed;

        private Run(List<JobRecord> nothingCommitted) {
            this.committed = nothingCommitted;
        }

        /** What the run has committed, as last reported. */
        List<JobRecord> committed() {
            return committed;
        }

        @Override
        public void close() {
            CURRENT.remove();
        }
    }
}
