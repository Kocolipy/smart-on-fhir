package com.example.backend.scheduling;

import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The in-memory job lock holds to the rules the SQL Server adapter does. Its transactions are a
 * {@link RecordingTransactionManager}'s, whose synchronization is Spring's own, so the lock sees
 * one open exactly as it would in production.
 */
class InMemoryScheduledJobLockContractTests extends ScheduledJobLockContract {

    private final InMemoryScheduledJobLock lock = new InMemoryScheduledJobLock();

    private final TransactionTemplate transactions =
            new TransactionTemplate(new RecordingTransactionManager());

    @Override
    protected ScheduledJobLock lock() {
        return lock;
    }

    @Override
    protected <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    @Override
    protected void heldByAnotherRun(ScheduledJob job) {
        lock.holdElsewhere(job);
    }
}
