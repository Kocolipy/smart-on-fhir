package com.example.backend.audit.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The sizing of the audit append pool, the dedicated connections fail-open appends commit on
 * (ADR 0004, 2026-10-13 addendum). Only sizing: the pool's URL, credentials and
 * {@code connection-init-sql} are the main datasource's, read from the main pool itself, so there
 * is nothing secret here and nothing that could drift from the main pool's role.
 *
 * <p>Bound from {@code APP_AUDIT_POOL_SIZE} and {@code APP_AUDIT_POOL_TIMEOUT} by relaxed binding;
 * the defaults are this record's, not a placeholder's.
 *
 * @param size    how many connections the pool holds at most, {@code APP_AUDIT_POOL_SIZE},
 *                default {@value #DEFAULT_SIZE}, and 2 or 3 only. Small on purpose: fail-open appends are single
 *                short inserts, and the point is that they never compete with requests
 * @param timeout how long an append waits for one of them before failing — and alerting —
 *                {@code APP_AUDIT_POOL_TIMEOUT}, default 2s. Short on purpose: a refusal's answer
 *                waits on it
 */
@ConfigurationProperties("app.audit.pool")
public record AuditAppendPoolProperties(Integer size, Duration timeout) {

    /** The pool's size when none is configured. */
    public static final int DEFAULT_SIZE = 2;

    /**
     * The bounds ADR 0004's addendum sets: two or three connections. Fewer leaves one concurrent
     * refusal waiting on another; more takes connections the database owes the main pool.
     */
    public static final int MINIMUM_SIZE = 2;

    /** See {@link #MINIMUM_SIZE}. */
    public static final int MAXIMUM_SIZE = 3;

    /** The connection timeout when none is configured. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /** Hikari's own floor for a connection timeout; a shorter one is silently raised to it. */
    public static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(250);

    public AuditAppendPoolProperties {
        size = size == null ? DEFAULT_SIZE : size;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (size < MINIMUM_SIZE || size > MAXIMUM_SIZE) {
            throw new IllegalArgumentException("APP_AUDIT_POOL_SIZE must be 2 or 3");
        }
        if (timeout.compareTo(MINIMUM_TIMEOUT) < 0) {
            throw new IllegalArgumentException("APP_AUDIT_POOL_TIMEOUT must be at least 250ms");
        }
    }
}
