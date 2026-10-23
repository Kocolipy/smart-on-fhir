package com.example.backend.auth.application;

import java.util.List;

/**
 * Work that must happen only once the surrounding transaction has committed.
 *
 * <p>Exists because a use case can own two kinds of write: one the database holds
 * inside a transaction, and one that reaches a system the transaction does not
 * cover — ending a session in Redis, most of all. Called inline, the second one
 * happens before the commit, so a rollback cannot take it back and a reader of
 * the committed row sees a state the outside world already moved past. This is
 * the seam that keeps the ordering the use case describes and the ordering that
 * executes the same one.
 *
 * <p>Deliberately not a transaction manager. Nothing here starts, commits, or
 * rolls anything back — a use case says only *this belongs after the commit*, and
 * the adapter decides how that is arranged.
 */
public interface AfterCommit {

    /**
     * Runs the given work once the current transaction commits, or immediately
     * when there is no transaction to wait for.
     *
     * <p>A rollback runs it never. It runs outside the transaction, so it must not
     * assume the persistence context is still open, and a failure in it cannot
     * undo the committed write — it surfaces to the caller with the write already
     * durable. Nor does it stop the other work handed over in the same transaction:
     * every piece runs, and the first failure surfaces once they all have.
     */
    void run(Runnable work);

    /**
     * Runs one transaction's after-commit work as {@link #run} promises: every piece, in order,
     * whatever an earlier one threw, and then throws the first failure with each later one
     * suppressed on it. Indexed rather than iterated, so a piece that hands over more work to the
     * same list runs it too. The one loop both the adapter and any stand-in for it run, so the
     * guarantee has a single definition.
     *
     * @param work the pieces, in the order they were handed over; read live, never copied
     */
    static void runEach(List<Runnable> work) {
        RuntimeException first = null;
        for (int next = 0; next < work.size(); next++) {
            try {
                work.get(next).run();
            } catch (RuntimeException failed) {
                if (first == null) {
                    first = failed;
                } else {
                    first.addSuppressed(failed);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
