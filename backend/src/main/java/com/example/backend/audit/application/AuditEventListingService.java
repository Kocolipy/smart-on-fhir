package com.example.backend.audit.application;

import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrative read of the audit trail.
 *
 * <p>Read-only and unaudited. Reading the trail is not one of the operations the trail records —
 * an event type for it would be a new event type, which this surface deliberately does not add —
 * and the caller is an Admin already authorized by the filter chain for {@code /api/admin/**}.
 *
 * <p>One read-only transaction, because the reader issues two statements — the count and the
 * page — and they must see one version of the trail: read separately, an event appended between
 * them would be listed but not counted. The reader puts the transaction at SQL Server's
 * {@code SNAPSHOT} isolation as its first act (ADR 0013), which Spring's {@code Isolation} cannot
 * name, so the total an Admin is shown describes exactly the events beside it. Nothing else may
 * read in this transaction before the reader does: a transaction that has read under another level
 * can no longer switch to {@code SNAPSHOT}.
 */
@Service
public class AuditEventListingService {

    private final AuditEventReader events;

    public AuditEventListingService(AuditEventReader events) {
        this.events = events;
    }

    @Transactional(readOnly = true)
    public AuditEventPage list(AuditEventQuery query) {
        return events.find(query);
    }
}
