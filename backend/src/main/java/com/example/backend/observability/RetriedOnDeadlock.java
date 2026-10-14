package com.example.backend.observability;

import com.example.backend.observability.LogEvent.Operation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A write whose transaction is retried once when the database chose it as a deadlock's victim
 * (the deadlock retry, {@code DeadlockRetryAdapter}, ADR 0015's as-13). Placed on the {@code @Transactional} method that
 * opens a SCIM or administrative write's transaction — the outermost transactional boundary —
 * and nowhere else: a read-only path or a scheduled run is not retried, because the next request
 * or run covers it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RetriedOnDeadlock {

    /** The operation the retry's {@code WARN} record is classified as: the write's own. */
    Operation value();
}
