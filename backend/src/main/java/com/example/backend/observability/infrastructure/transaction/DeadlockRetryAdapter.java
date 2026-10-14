package com.example.backend.observability.infrastructure.transaction;

import com.example.backend.observability.DatabaseFailureCategory;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.RetriedOnDeadlock;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retries a write once when the database chose its transaction as a deadlock's victim — ADR
 * 0015's as-13, the single interceptor at the outermost transactional boundary that
 * {@code ScimWriteLocks} leaves the deadlock it cannot prevent to.
 *
 * <p>A deadlock victim's transaction is rolled back whole, so nothing it did stands and running
 * it again is the same request arriving a moment later. The retry is therefore applied only
 * where a whole transaction begins: on a {@link RetriedOnDeadlock} method, outside its
 * {@code @Transactional} interceptor, so each attempt opens a transaction of its own. It is an
 * infrastructure advisor, as Spring's transaction advisor is — the auto-proxy creator that
 * applies {@code @Transactional} adopts only those — so it joins the same proxy, ordered
 * {@link Ordered#HIGHEST_PRECEDENCE}, ahead of the transaction.
 *
 * <ul>
 *   <li>The first deadlock is written at {@code WARN} ({@link LogEvent#deadlockRetried}:
 *       {@code error.category} {@code database}, {@code app.retry.attempt} {@code 1},
 *       {@code event.reason} {@code deadlock}, no follow-up) and the write runs again.
 *   <li>A deadlock on the retry propagates as it was raised, and the request's fault handler
 *       writes it as the {@code ERROR} it is — {@code database}, investigate — through
 *       {@link DatabaseFailureCategory}.
 *   <li>Any other failure propagates untouched: a serialization or snapshot conflict, a lock not
 *       acquired, a refusal. None of them is retried.
 *   <li>Never nested: a marked method called inside a transaction that is already open joins it,
 *       and a transaction cannot be retried from inside itself, so such a call is not retried.
 * </ul>
 *
 * <p>Reads whether a transaction is open, and registers nothing to run at its end: after-commit
 * work stays the {@code AfterCommit} port's (ADR 0002).
 */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class DeadlockRetryAdapter extends DefaultPointcutAdvisor {

    private static final Logger log = LoggerFactory.getLogger(DeadlockRetryAdapter.class);

    public DeadlockRetryAdapter() {
        super(AnnotationMatchingPointcut.forMethodAnnotation(RetriedOnDeadlock.class), new Retry());
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    /** The retry itself, around one call of a marked method. */
    private static final class Retry implements MethodInterceptor {

        /**
         * Each attempt proceeds from a copy of the invocation taken before any attempt ran, so
         * the retry passes through every interceptor beneath this one again — the transaction's
         * included — rather than straight to the method.
         */
        @Override
        public Object invoke(MethodInvocation invocation) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                return proceed(invocation);
            }
            ProxyMethodInvocation call = (ProxyMethodInvocation) invocation;
            try {
                return proceed(call.invocableClone());
            } catch (RuntimeException failure) {
                if (!DatabaseFailureCategory.deadlock(failure)) {
                    throw failure;
                }
                LogEvent.deadlockRetried(log, operation(invocation)).log();
                return proceed(call.invocableClone());
            }
        }

        /**
         * One attempt. A marked write declares no checked exception — every write this service
         * retries is a {@code @Transactional} application method raising unchecked ones — so a
         * checked throwable reaching here is wrapped as the JDK's proxies wrap one a method did
         * not declare.
         */
        private static Object proceed(MethodInvocation attempt) {
            try {
                return attempt.proceed();
            } catch (RuntimeException | Error unchecked) {
                throw unchecked;
            } catch (Throwable checked) {
                throw new UndeclaredThrowableException(checked);
            }
        }

        /**
         * The write's operation, as its {@link RetriedOnDeadlock} names it — read from the
         * target's own method, the one the pointcut matched, whichever type the proxy was called
         * as.
         */
        private static Operation operation(MethodInvocation invocation) {
            Method method = AopUtils.getMostSpecificMethod(
                    invocation.getMethod(), AopUtils.getTargetClass(invocation.getThis()));
            return method.getAnnotation(RetriedOnDeadlock.class).value();
        }
    }
}
