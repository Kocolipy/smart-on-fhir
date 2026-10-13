package com.example.backend.audit.infrastructure.persistence;

import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.IsolatedAuditEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Maps the fail-open append onto the dedicated audit pool, as one plain JDBC {@code INSERT}.
 *
 * <p>The {@link JdbcTemplate} is the audit append pool's ({@code AuditAppendPoolConfig}): two or
 * three connections of their own, opened with the main pool's URL, credentials and
 * {@code connection-init-sql}, so an append runs as the same runtime role and never takes a
 * connection from the main pool. The pool's connections auto-commit, so the one statement is a
 * transaction of its own — committed whatever the caller's transaction then does — with no
 * transaction manager to join, and none the caller's could leak into.
 *
 * <p>JDBC rather than JPA: the persistence context belongs to the main pool's transaction manager,
 * and a second one would be a second Hibernate set-up for one insert. The columns are those
 * {@link AuditEventPersistenceAdapter} maps through the entity, in the same shapes; both adapters
 * write every row the listing reads back, so a column one of them forgot fails the recording
 * tests. Instants are bound as {@link OffsetDateTime} at UTC, the rule hand-written JDBC follows
 * (ADR 0015).
 */
@Repository
class IsolatedAuditEventPersistenceAdapter implements IsolatedAuditEventRepository {

    /**
     * Its column list is the entity's mapped columns, exactly:
     * {@code IsolatedAuditEventPersistenceAdapterTests} holds the two to each other.
     */
    static final String INSERT = """
            INSERT INTO audit_events (
                id, occurred_at, operation, outcome, actor_id, subject_id, resource_type,
                resource_id, changed_paths, status_class, error_code, http_method, http_path,
                request_id, result_count, filter_shape, role_name, permissions, login_method,
                mfa_factor, token_id, replacement_token_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * The audit append pool's template, by the qualifier {@code AuditAppendPoolConfig} declares it
     * under — spelled in both, since a configuration and a persistence adapter may not depend on
     * each other; a mismatch fails every context's startup.
     */
    static final String AUDIT_APPEND_JDBC = "auditAppendJdbcTemplate";

    private final JdbcTemplate pool;

    IsolatedAuditEventPersistenceAdapter(@Qualifier(AUDIT_APPEND_JDBC) JdbcTemplate pool) {
        this.pool = pool;
    }

    @Override
    public void append(AuditEvent event) {
        pool.update(INSERT,
                event.id(),
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                event.operation().name(),
                event.outcome().name(),
                event.actorId(),
                event.subjectId(),
                event.resourceType(),
                event.resourceId(),
                AuditEventPersistenceAdapter.joinPaths(event.changedPaths()),
                event.statusClass(),
                event.errorCode(),
                event.httpMethod(),
                event.httpPath(),
                event.requestId(),
                event.resultCount(),
                event.filterShape(),
                event.role(),
                AuditEventPersistenceAdapter.joinPaths(event.permissions()),
                event.loginMethod(),
                event.mfaFactor(),
                event.tokenId(),
                event.replacementTokenId());
    }
}
