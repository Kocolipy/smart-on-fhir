package com.example.backend.scheduling.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.database.ConnectionQualifiers;
import com.example.backend.scheduling.ScheduledJobLockContract;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The production job lock — the JDBC adapter's row lock over SQL Server — holds to the rules the
 * in-memory lock does. Another run is a transaction on another thread, and so on another
 * connection, holding the job's row until the case ends; the case's own asks run in transactions
 * of their own on the main pool, as {@code ScheduledJobRunService} opens them.
 *
 * <p>The lock rows are the migration's and nothing here writes, so it shares the plain cached
 * context {@code ScheduledJobRunIntegrationTests} carries with nothing to clean up.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class SqlServerScheduledJobLockContractTests extends ScheduledJobLockContract {

    @Autowired
    private ScheduledJobLock lock;

    @Autowired
    @Qualifier(ConnectionQualifiers.MAIN_TRANSACTIONS)
    private PlatformTransactionManager transactions;

    private final ExecutorService otherRuns = Executors.newCachedThreadPool();

    private final CountDownLatch caseEnded = new CountDownLatch(1);

    private final List<Future<Boolean>> held = new ArrayList<>();

    /** Ends every other run's transaction, so its lock goes with it, and checks each took its lock. */
    @AfterEach
    void endTheOtherRuns() throws Exception {
        caseEnded.countDown();
        try {
            for (Future<Boolean> run : held) {
                assertThat(run.get(60, TimeUnit.SECONDS)).as("the other run took its lock").isTrue();
            }
        } finally {
            otherRuns.shutdownNow();
        }
    }

    @Override
    protected ScheduledJobLock lock() {
        return lock;
    }

    @Override
    protected <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    @Override
    protected void heldByAnotherRun(ScheduledJob job) {
        CountDownLatch taken = new CountDownLatch(1);
        held.add(otherRuns.submit(() -> inTransaction(() -> {
            boolean acquired = lock.tryAcquire(job);
            taken.countDown();
            awaitQuietly(caseEnded);
            return acquired;
        })));
        awaitQuietly(taken);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).as("waited in time").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
