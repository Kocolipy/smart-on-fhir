package com.example.backend.auth.infrastructure.transaction;

import com.example.backend.auth.application.AfterCommit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Outbound adapter for {@link AfterCommit}, backed by Spring's transaction
 * synchronization registry.
 *
 * <p>Registers one synchronization on the transaction bound to the calling thread,
 * the first time work is handed over in it, and queues every later piece on that
 * same synchronization, so the work runs after that transaction commits, in the
 * order it was handed over, and not at all if it rolls back.
 *
 * <p>One synchronization rather than one per piece, because Spring runs a
 * transaction's synchronizations one after another and stops at the first that
 * throws: a revocation whose session store failed would otherwise leave every
 * revocation after it unrun, unaudited and unalerted — one member's timeout keeping
 * the rest of a Group signed in. The queue runs every piece whatever an earlier one
 * did, and then throws the first failure, carrying each later one as suppressed, so
 * the caller still sees the request fail as the port promises. Nothing else in the
 * application registers a synchronization, so the work keeps its place among them.
 *
 * <p>With no transaction bound, the work runs immediately. That is the honest
 * reading of the contract rather than a fallback that hides a mistake: without a
 * transaction there is no commit to wait for and nothing that could roll the
 * caller's write back, so "after the commit" and "now" are the same instant.
 */
@Component
public class AfterCommitAdapter implements AfterCommit {

    @Override
    public void run(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        queueOfCurrentTransaction().add(work);
    }

    /**
     * The queue registered on the transaction bound to this thread, registered now if this is
     * the first work handed over in it. A transaction suspended for a nested one keeps its own:
     * the registry lists only the synchronizations of the transaction currently bound.
     */
    private static Queue queueOfCurrentTransaction() {
        for (TransactionSynchronization registered
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (registered instanceof Queue queue) {
                return queue;
            }
        }
        Queue queue = new Queue();
        TransactionSynchronizationManager.registerSynchronization(queue);
        return queue;
    }

    /** One transaction's after-commit work, run in full once it commits. */
    private static final class Queue implements TransactionSynchronization {

        private final List<Runnable> pending = new ArrayList<>();

        void add(Runnable work) {
            pending.add(work);
        }

        /** Runs every piece, as {@link AfterCommit#runEach} defines. */
        @Override
        public void afterCommit() {
            AfterCommit.runEach(pending);
        }
    }
}
