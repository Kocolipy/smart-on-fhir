package com.example.backend.auth;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager with no resource behind it, which counts the transactions it was asked to
 * open: a module that opens its own transaction runs over it as over the real one, and a test can
 * say how many it opened, committed and rolled back.
 *
 * <p>It runs nothing on commit: the work that waits for a commit is {@link PendingCommit}'s, which
 * a test drives by hand, so the two orderings stay told apart.
 */
public final class RecordingTransactions implements PlatformTransactionManager {

    private int begun;
    private int committed;
    private int rolledBack;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        begun++;
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
        committed++;
    }

    @Override
    public void rollback(TransactionStatus status) {
        rolledBack++;
    }

    /** How many transactions were opened. */
    public int begun() {
        return begun;
    }

    /** How many were committed. */
    public int committed() {
        return committed;
    }

    /** How many were rolled back. */
    public int rolledBack() {
        return rolledBack;
    }
}
