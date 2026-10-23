package com.example.backend.auth;

import com.example.backend.auth.application.AfterCommit;
import java.util.ArrayList;
import java.util.List;

/**
 * A transaction a test drives by hand, standing in for the Spring
 * synchronization adapter.
 *
 * <p>Deliberately does not run the work as it is handed over: the whole point of
 * the port is that the work waits for the commit, and a double that ran it
 * immediately could not tell the two orderings apart. A test that wants the
 * deferred work to happen says {@link #commit()}; one that wants a failed
 * transaction says {@link #rollback()}, or simply never commits.
 *
 * <p>Models what the adapter guarantees of a failure by running the same loop,
 * {@link AfterCommit#runEach}: every piece of work runs whatever an earlier one threw, and the
 * commit then throws the first failure, each later one suppressed on it.
 */
public final class PendingCommit implements AfterCommit {

    private final List<Runnable> pending = new ArrayList<>();

    @Override
    public void run(Runnable work) {
        pending.add(work);
    }

    /** How much work is waiting on the commit. */
    public int pending() {
        return pending.size();
    }

    /**
     * Commits, running everything that was waiting on it, in order — all of it, even after a
     * piece fails, and any work a piece hands over in the meantime, as the adapter's queue
     * does — and then throws the first failure, with each later one suppressed on it.
     */
    public void commit() {
        try {
            AfterCommit.runEach(pending);
        } finally {
            pending.clear();
        }
    }

    /** Rolls back, discarding the work unrun. */
    public void rollback() {
        pending.clear();
    }
}
