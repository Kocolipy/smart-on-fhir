package com.example.backend.audit.domain;

import java.time.Instant;

/**
 * The one capability that may remove a recorded event: deleting what has aged out
 * of the retention window, one batch at a time.
 *
 * <p>A separate port from {@link AuditEventRepository} rather than another method
 * on it, because the two are performed with different database privileges. The
 * application's own login holds insert and read on the audit table; the delete
 * belongs to the retention login, reserved for this, and the adapter behind this
 * port is the only place in the service that connects as it.
 */
public interface AuditEventRetention {

    /**
     * The most events one batch deletes: below SQL Server's lock-escalation threshold of 5,000
     * locks taken by one statement on one table or index, so a batch holds row locks only and
     * never the table lock that would block an audit append for as long as it runs (ADR 0015).
     */
    int BATCH_SIZE = 4000;

    /**
     * Deletes up to {@link #BATCH_SIZE} events recorded strictly before {@code cutoff}, in a
     * transaction of its own, committed by the time this returns.
     *
     * @return how many rows were removed; fewer than {@link #BATCH_SIZE} once nothing recorded
     *         before {@code cutoff} remains
     */
    long deleteBatchOccurredBefore(Instant cutoff);
}
