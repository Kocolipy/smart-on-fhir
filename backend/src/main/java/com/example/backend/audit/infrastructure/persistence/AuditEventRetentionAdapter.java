package com.example.backend.audit.infrastructure.persistence;

import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.database.ConnectionQualifiers;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes aged-out audit events, and is the only code in this service that reaches the retention
 * pool — the connections of the retention login, the one principal the audit table lets delete
 * (ADR 0015, "Runtime model").
 *
 * <p>JDBC rather than JPA: this is a set-based delete whose row count is the thing
 * the caller reports, and loading entities to remove them would read the whole
 * expired window into memory to learn a number the database already knows.
 *
 * <p>The delete commits in a transaction of the retention pool's own transaction manager, named
 * here rather than resolved by type, because the default one is the main pool's: a delete there
 * would run as the app login and be refused. It is called inside the scheduled-job run's
 * transaction on the main pool, which holds the job's lock for the whole run (ADR 0005); this
 * transaction is on another data source, so it opens, commits and ends inside that one and
 * releases nothing.
 */
@Repository
class AuditEventRetentionAdapter implements AuditEventRetention {

    private static final String DELETE_AGED_OUT =
            "DELETE FROM audit_events WHERE occurred_at < ?";

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc the retention pool's template, by the qualifier
     *             {@code audit.config.AuditRetentionPoolConfig} declares it under — both spell it
     *             through {@link ConnectionQualifiers}, since a persistence adapter and a
     *             configuration may not depend on each other
     */
    AuditEventRetentionAdapter(@Qualifier(ConnectionQualifiers.AUDIT_RETENTION_JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The cutoff is bound as an offset at UTC, so the same rows age out in any JVM zone (ADR
     * 0015, "Storage is UTC only").
     */
    @Override
    @Transactional(transactionManager = ConnectionQualifiers.AUDIT_RETENTION_TRANSACTIONS)
    public long deleteOccurredBefore(Instant cutoff) {
        return jdbc.update(DELETE_AGED_OUT, cutoff.atOffset(ZoneOffset.UTC));
    }
}
