package com.example.backend.observability.infrastructure.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.ExhaustedRetry;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.observability.SqlFailures;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/**
 * The deadlock retry, seen as a caller of an annotated write sees it: through a proxy carrying
 * the {@link DeadlockRetryAdapter} beside an advisor standing for Spring's transaction advisor,
 * the two applied in the order the auto-proxy creator applies them, with the records the retry
 * writes read back from the production ECS encoder.
 *
 * <p>The stand-in models what makes the order matter: a deadlock aborts the transaction it struck
 * in, and an aborted transaction refuses every later statement, so a retry run inside the
 * victim's transaction fails where one run outside it, in a transaction of its own, completes.
 *
 * <p>The deadlock is the exception a SQL Server deadlock victim's write (error 1205) raises
 * through JPA:
 * Spring's {@link CannotAcquireLockException} over the driver's {@link SQLException}, raised as
 * {@link SqlFailures#DEADLOCK_VICTIM}.
 */
class DeadlockRetryAdapterTests {

    private static final String DEADLOCK_RETRIED = "Database deadlock: the write is retried once";

    private EcsLogCapture logs;

    @BeforeEach
    void attachLogs() {
        logs = EcsLogCapture.attach(new StandardEnvironment());
    }

    @AfterEach
    void detachLogs() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aWriteThatSucceedsIsReturnedAsItIs() {
        assertThat(proxied(new Writes()).write()).isEqualTo("committed:written");
        assertThat(retried()).isEmpty();
    }

    @Test
    void aDeadlockOnTheFirstAttemptIsRetriedAndTheWriteCompletes() {
        Writes writes = proxied(new Writes(deadlock()));

        assertThat(writes.write()).isEqualTo("committed:written");
    }

    /**
     * The retried deadlock is one {@code WARN}: a {@code database} error needing no follow-up,
     * its first retry, for a deadlock — classified as the write's own operation, with no outcome,
     * which the retried write's own records state.
     */
    @Test
    void theRetriedDeadlockIsOneWarnNamingTheRetryAndItsReason() {
        proxied(new Writes(deadlock())).write();

        assertThat(retried()).singleElement().satisfies(warn -> {
            assertThat(warn.at("/log/level").asText()).isEqualTo("WARN");
            assertThat(warn.at("/error_category").asText()).isEqualTo("database");
            assertThat(warn.at("/error_follow_up_action").isMissingNode()).isTrue();
            assertThat(warn.at("/error_code").isMissingNode()).isTrue();
            assertThat(warn.at("/app/retry/attempt").asInt()).isEqualTo(1);
            assertThat(warn.at("/event/reason").asText()).isEqualTo("deadlock");
            assertThat(warn.at("/event/action").asText()).isEqualTo("user-provisioning");
            assertThat(warn.at("/app/event/action").asText()).isEqualTo("scim.write");
            assertThat(warn.at("/event/category").toString()).isEqualTo("[\"database\"]");
            assertThat(warn.at("/event/type").toString()).isEqualTo("[\"error\"]");
            assertThat(warn.at("/event/outcome").isMissingNode()).isTrue();
        });
    }

    /**
     * A deadlock on the retry is the write's failure: it propagates as raised, for the request's
     * fault handler to write as its {@code ERROR}, and the write is not tried a third time.
     */
    @Test
    void aSecondDeadlockPropagatesAfterTheOneRetry() {
        CannotAcquireLockException second = deadlock();
        Writes writes = proxied(new Writes(deadlock(), second));

        assertThatThrownBy(writes::write).isSameAs(second);
        assertThat(retried()).hasSize(1);
    }

    /**
     * The second deadlock is the one ADR 0015's as-13 answers {@code 503}: the retry marks the
     * request it ended, so the request's fault handler tells it from a deadlock nothing retried.
     */
    @Test
    void aSecondDeadlockMarksTheRequestAsItsRetryExhausted() {
        servingARequest();
        CannotAcquireLockException second = deadlock();
        Writes writes = proxied(new Writes(deadlock(), second));

        assertThatThrownBy(writes::write).isSameAs(second);
        assertThat(ExhaustedRetry.answers(second)).isTrue();
    }

    /** A write served on no request — a test's, a job's — has nothing to mark or to answer. */
    @Test
    void aSecondDeadlockOnNoRequestIsAnsweredByNoOne() {
        CannotAcquireLockException second = deadlock();
        Writes writes = proxied(new Writes(deadlock(), second));

        assertThatThrownBy(writes::write).isSameAs(second);
        assertThat(ExhaustedRetry.answers(second)).isFalse();
    }

    @Test
    void aRetryThatCompletesLeavesTheRequestUnmarked() {
        servingARequest();
        CannotAcquireLockException first = deadlock();
        proxied(new Writes(first)).write();

        assertThat(ExhaustedRetry.answers(first)).isFalse();
    }

    /** A retry that fails for any other reason is that failure's, and no exhausted retry. */
    @Test
    void aRetryThatFailsForAnotherReasonLeavesTheRequestUnmarked() {
        servingARequest();
        CannotAcquireLockException first = deadlock();
        IllegalStateException bug = new IllegalStateException("bug");
        Writes writes = proxied(new Writes(first, bug));

        assertThatThrownBy(writes::write).isSameAs(bug);
        assertThat(ExhaustedRetry.answers(first)).isFalse();
    }

    /**
     * A serialization or snapshot conflict is not a deadlock: ADR 0015 retries only the deadlock
     * victim, and the conflict is the {@code ERROR} of the mapping's second row.
     */
    @Test
    void aSerializationConflictIsNotRetried() {
        CannotAcquireLockException conflict = new CannotAcquireLockException("serialize",
                SqlFailures.SNAPSHOT_CONFLICT.raised("snapshot isolation update conflict"));
        Writes writes = proxied(new Writes(conflict));

        assertThatThrownBy(writes::write).isSameAs(conflict);
        assertThat(retried()).isEmpty();
    }

    @Test
    void aFailureThatIsNotTheDatabasesIsNotRetried() {
        IllegalStateException bug = new IllegalStateException("bug");
        Writes writes = proxied(new Writes(bug));

        assertThatThrownBy(writes::write).isSameAs(bug);
    }

    /** The deadlock is found beneath any wrapper, by its SQLState. */
    @Test
    void aDeadlockBeneathAnyWrapperIsRetried() {
        Writes writes = proxied(new Writes(new IllegalStateException("wrapped", deadlock())));

        assertThat(writes.write()).isEqualTo("committed:written");
    }

    /**
     * Never nested: a marked write called inside a transaction already open joins it, and the
     * victim's transaction is that outer one, which no inner call can run again.
     */
    @Test
    void aWriteJoiningAnOpenTransactionIsNotRetried() {
        CannotAcquireLockException deadlock = deadlock();
        Writes writes = proxied(new Writes(deadlock));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(writes::write).isSameAs(deadlock);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(retried()).isEmpty();
    }

    @Test
    void aWriteJoiningAnOpenTransactionReturnsItsResult() {
        Writes writes = proxied(new Writes());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(writes.write()).isEqualTo("committed:written");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * A write called through an interface its class implements, where only the class's method
     * is marked, is retried and classified as that method names.
     */
    @Test
    void aWriteCalledThroughItsInterfaceIsRetriedUnderTheClasssOperation() {
        ProxyFactory proxy = new ProxyFactory(new Writes(deadlock()));
        proxy.setInterfaces(Write.class);
        advise(proxy);
        Write write = (Write) proxy.getProxy();

        assertThat(write.write()).isEqualTo("committed:written");
        assertThat(retried()).singleElement().satisfies(warn ->
                assertThat(warn.at("/app/event/action").asText()).isEqualTo("scim.write"));
    }

    /**
     * A checked throwable a marked write raised reaches its caller wrapped, as a proxy wraps one
     * the method did not declare, and is not retried.
     */
    @Test
    void aCheckedFailureReachesTheCallerWrapped() {
        Exception checked = new Exception("checked");
        Writes writes = proxied(new Writes() {
            @Override
            @RetriedOnDeadlock(Operation.SCIM_WRITE)
            public String write() {
                throw sneaky(checked);
            }
        });

        assertThatThrownBy(writes::write)
                .isInstanceOf(UndeclaredThrowableException.class)
                .cause().isSameAs(checked);
    }

    /** Only a method marked for the retry is retried. */
    @Test
    void anUnmarkedMethodIsNotRetried() {
        CannotAcquireLockException deadlock = deadlock();
        Writes writes = proxied(new Writes(deadlock));

        assertThatThrownBy(writes::unmarked).isSameAs(deadlock);
    }

    // ---- harness -----------------------------------------------------------------------------

    /** Throws {@code failure}, checked or not, from a method that declares nothing. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void servingARequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException("could not execute statement",
                SqlFailures.DEADLOCK_VICTIM.raised("ERROR: deadlock detected"));
    }

    /** The transaction the stand-in opened for the current attempt. */
    private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();

    /** One transaction of the stand-in's: aborted once a deadlock struck in it. */
    private static final class Transaction {
        private boolean aborted;
    }

    /** {@code target}, class-proxied as the application's services are. */
    private static Writes proxied(Writes target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        advise(proxy);
        return (Writes) proxy.getProxy();
    }

    /**
     * The retry and the transaction stand-in, sorted by their order as the auto-proxy creator
     * sorts the advisors it applies — the stand-in inserted first, so only the retry's own order
     * can put it outside.
     */
    private static void advise(ProxyFactory proxy) {
        List<Advisor> advisors = new ArrayList<>(List.of(transaction(), new DeadlockRetryAdapter()));
        AnnotationAwareOrderComparator.sort(advisors);
        advisors.forEach(proxy::addAdvisor);
    }

    /**
     * Stands for Spring's transaction advisor, at its default order, last: each call opens a
     * transaction of its own and marks what it committed.
     */
    private static Advisor transaction() {
        MethodInterceptor transaction = invocation -> {
            Transaction outer = CURRENT.get();
            CURRENT.set(new Transaction());
            try {
                return "committed:" + invocation.proceed();
            } finally {
                CURRENT.set(outer);
            }
        };
        return new DefaultPointcutAdvisor(transaction);
    }

    private List<JsonNode> retried() {
        return logs.records().stream()
                .filter(record -> DEADLOCK_RETRIED.equals(record.at("/message").asText()))
                .toList();
    }

    /** A write, as an interface a proxy may be called through. */
    interface Write {
        String write();
    }

    /**
     * A write that fails with each queued failure in turn, then succeeds — in the transaction the
     * stand-in opened, which the failure aborts, as a deadlock aborts the victim's.
     */
    static class Writes implements Write {

        private final Deque<RuntimeException> failures;

        Writes(RuntimeException... failures) {
            this.failures = new ArrayDeque<>(List.of(failures));
        }

        @Override
        @RetriedOnDeadlock(Operation.SCIM_WRITE)
        public String write() {
            Transaction transaction = CURRENT.get();
            if (transaction.aborted) {
                throw new IllegalStateException("current transaction is aborted");
            }
            RuntimeException failure = failures.poll();
            if (failure != null) {
                transaction.aborted = true;
                throw failure;
            }
            return "written";
        }

        /** The same write, not marked for the retry. */
        public String unmarked() {
            return write();
        }
    }
}
