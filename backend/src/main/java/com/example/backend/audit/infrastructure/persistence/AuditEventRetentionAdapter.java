package com.example.backend.audit.infrastructure.persistence;

import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.database.ConnectionQualifiers;
import com.example.backend.database.TimedJdbc;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes aged-out audit events, a batch at a time, and is the only code in this service that
 * reaches the retention pool — the connections of the retention login, the one principal the
 * audit table lets delete (ADR 0015, "Runtime model").
 *
 * <p>JDBC rather than JPA: this is a set-based delete whose row count is the thing
 * the caller reports, and loading entities to remove them would read the whole
 * expired window into memory to learn a number the database already knows.
 *
 * <p>A batch is {@code DELETE TOP (4000)}: below SQL Server's lock-escalation threshold, so it
 * holds key locks only, never the table lock an audit append would queue behind. The table is
 * clustered on {@code (occurred_at, id)}, so the batch is a range at the old end of it, away
 * from where appends land. The delete reaches the table's append-only trigger, which lets the
 * retention login's delete through.
 *
 * <p>Each batch commits in a transaction of the retention pool's own transaction manager, named
 * here rather than resolved by type, because the default one is the main pool's: a delete there
 * would run as the app login and be refused. It is called inside the scheduled-job run's
 * transaction on the main pool, which holds the job's lock for the whole run (ADR 0005); this
 * transaction is on another data source, so it opens, commits and ends inside that one and
 * releases nothing — not even between two batches.
 */
@Repository
class AuditEventRetentionAdapter implements AuditEventRetention {

    private static final String DELETE_A_BATCH =
            "DELETE TOP (?) FROM audit_events WHERE occurred_at < ?";

    /** How long one batch may run before it fails the run (ADR 0015, "Bounded timeouts"). */
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;

    /**
     * @param pool the retention pool's template, by the qualifier
     *             {@code audit.config.AuditRetentionPoolConfig} declares it under — both spell it
     *             through {@link ConnectionQualifiers}, since a persistence adapter and a
     *             configuration may not depend on each other. Its data source is used under a
     *             template of this adapter's own, which times each batch out.
     */
    AuditEventRetentionAdapter(
            @Qualifier(ConnectionQualifiers.AUDIT_RETENTION_JDBC) JdbcTemplate pool) {
        this.jdbc = TimedJdbc.over(pool, BATCH_TIMEOUT);
    }

    /**
     * The cutoff is bound as an offset at UTC, so the same rows age out in any JVM zone (ADR
     * 0015, "Storage is UTC only").
     */
    @Override
    @Transactional(transactionManager = ConnectionQualifiers.AUDIT_RETENTION_TRANSACTIONS)
    public long deleteBatchOccurredBefore(Instant cutoff) {
        return jdbc.update(DELETE_A_BATCH, BATCH_SIZE, cutoff.atOffset(ZoneOffset.UTC));
    }
}
