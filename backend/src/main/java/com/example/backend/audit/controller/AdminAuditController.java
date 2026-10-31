package com.example.backend.audit.controller;

import com.example.backend.audit.application.AuditEventListingService;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for the redacted administrative audit listing.
 *
 * <p>Under {@code /api/admin}, so it is session authenticated; it requires {@code audit:read},
 * declared here with method security and repeated by the chain as a backstop, for the reason
 * {@code AdminAccountController} gives.
 *
 * <p>Every filter binds to a typed value — an operation or outcome from its closed set, an id, an
 * instant — so a malformed one is refused with {@code 400} by the binder before this method runs,
 * and none can carry free text into the query. {@code startIndex} and {@code count} are coerced,
 * never refused, by the same rules as the Users and Groups listings ({@link PageRequest#of}); one
 * that is not an integer at all is the binder's {@code 400}, as a malformed filter is.
 */
@RestController
@RequestMapping("/api/admin/audit-events")
public class AdminAuditController {

    private final AuditEventListingService listing;

    public AdminAuditController(AuditEventListingService listing) {
        this.listing = listing;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public AuditEventPage list(
            @RequestParam(required = false) AuditOperation operation,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count) {
        return listing.list(new AuditEventQuery(
                operation, outcome, actorId, resourceId, from, to,
                PageRequest.of(startIndex, count)));
    }
}
