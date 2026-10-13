package com.example.backend.scheduling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager with no database behind it, for a unit test of a scheduled job: it opens,
 * suspends, resumes, commits and rolls back the way Spring's own do, so the transaction
 * synchronization a caller sees is real, and it writes down each step it took.
 *
 * <p>A test may pass in the list the steps go to, so they interleave with steps of its own.
 */
public final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    private final List<String> steps;

    private final Deque<Object> open = new ArrayDeque<>();

    public RecordingTransactionManager() {
        this(new ArrayList<>());
    }

    public RecordingTransactionManager(List<String> steps) {
        this.steps = steps;
    }

    /** Every step taken, in order: {@code begin}, {@code suspend}, {@code resume},
     *  {@code commit} and {@code rollback}. */
    public List<String> steps() {
        return List.copyOf(steps);
    }

    @Override
    protected Object doGetTransaction() {
        return new Transaction(open.peek());
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Transaction) transaction).existing != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        open.push(transaction);
        steps.add("begin");
    }

    @Override
    protected Object doSuspend(Object transaction) {
        steps.add("suspend");
        return open.pop();
    }

    @Override
    protected void doResume(Object transaction, Object suspended) {
        open.push(suspended);
        steps.add("resume");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        steps.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        steps.add("rollback");
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        open.remove(transaction);
    }

    /** Compared by identity, so ending one transaction never closes another. */
    private static final class Transaction {

        private final Object existing;

        private Transaction(Object existing) {
            this.existing = existing;
        }
    }
}
