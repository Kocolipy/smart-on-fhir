package com.example.backend.audit.infrastructure.persistence;

import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventRepository;
import com.example.backend.audit.infrastructure.persistence.entity.AuditEventEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Maps the append-only audit port onto JPA. */
@Repository
class AuditEventPersistenceAdapter implements AuditEventRepository {

    /**
     * Separator for the changed-path list. A comma is safe because every path in
     * the vocabulary is a Java attribute name — the application never puts a
     * caller's value in this list, so nothing can contain the separator.
     */
    private static final String PATH_SEPARATOR = ",";

    private final AuditEventJpaRepository events;

    AuditEventPersistenceAdapter(AuditEventJpaRepository events) {
        this.events = events;
    }

    /**
     * {@code saveAndFlush} rather than {@code save}: the insert has to reach the
     * database at this call so that a refused privilege or a violated constraint
     * fails the caller's transaction here, while the caller is still the thing on
     * the stack. Deferred to the commit it would surface after every use case had
     * returned, and the fail-closed contract would be a promise nobody could keep.
     */
    @Override
    public void append(AuditEvent event) {
        events.saveAndFlush(new AuditEventEntity(
                event.id(),
                event.occurredAt(),
                event.operation(),
                event.outcome(),
                event.actorId(),
                event.subjectId(),
                event.resourceType(),
                event.resourceId(),
                joinPaths(event.changedPaths()),
                event.statusClass(),
                event.errorCode(),
                event.httpMethod(),
                event.httpPath(),
                event.requestId(),
                event.resultCount(),
                event.filterShape(),
                event.role(),
                joinPaths(event.permissions()),
                event.loginMethod()));
    }

    /** An empty list is stored as null, so "nothing changed" — or "none named" — is one value. */
    private static String joinPaths(List<String> paths) {
        return paths.isEmpty() ? null : String.join(PATH_SEPARATOR, paths);
    }
}
