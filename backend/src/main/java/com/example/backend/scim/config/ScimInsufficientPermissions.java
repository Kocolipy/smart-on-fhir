package com.example.backend.scim.config;

import com.example.backend.audit.domain.AuditRequest;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.AccessRefusalLog.Refusal;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.RequestActor;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.observability.RouteTemplates;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The SCIM chain's one answer to a valid token lacking the Permission a request needs, whichever
 * layer decided it (ADR 0010).
 *
 * <p>Two layers decide. The handler's own {@code @PreAuthorize} declaration is the authority: its
 * refusal propagates out of the dispatcher — the namespace's controller advice deliberately does
 * not answer it — to the chain's exception translation, which hands it here as the chain's
 * {@link AccessDeniedHandler}. The bearer filter's request-line rule is the backstop, and calls
 * {@link #refuse} itself. Both therefore produce the same exchange: one {@code
 * insufficient-permissions} refusal record naming the connector, one connector access-denied audit
 * row naming the operation by its method and route template — written only when the log took its
 * record, so one exchange is one record of each kind — and the bearer {@code 403}
 * {@code insufficient_scope} challenge with no body. None of the three names the Permission that
 * was missing.
 *
 * <p>The connector is read from the request mark the bearer filter left ({@link RequestActor}),
 * not from the security context: by the time exception translation runs, the filter has already
 * cleared that context as the chain unwound.
 */
final class ScimInsufficientPermissions implements AccessDeniedHandler {

    private final AccessRefusalLog refusals;

    private final AuditTrail audit;

    private final RouteTemplates routes;

    ScimInsufficientPermissions(AccessRefusalLog refusals, AuditTrail audit, RouteTemplates routes) {
        this.refusals = refusals;
        this.audit = audit;
        this.routes = routes;
    }

    /** A handler's Permission declaration refused the request. */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException refused) {
        // Every request a handler serves on this chain was authenticated by a connector's token,
        // so the mark is always there.
        UUID connectorId = RequestActor.connectorOf(request);
        try (LogContext.Scope scope = LogContext.connectorId(connectorId.toString())) {
            refuse(request, response, connectorId);
        }
    }

    /**
     * Records, audits and answers the refusal of a request made by this connector. The caller
     * holds the connector's logging context open around the call.
     */
    void refuse(HttpServletRequest request, HttpServletResponse response, UUID connectorId) {
        if (refusals.record(request, Refusal.INSUFFICIENT_PERMISSIONS)) {
            // Only when the log took it, so one exchange is one record of each kind.
            audit.append(new Audited.ConnectorAccessDenied(
                    connectorId,
                    new AuditRequest(RequestIdFilter.method(request), routes.of(request),
                            RequestIdFilter.requestId(request))));
        }
        ScimBearerChallenge.insufficientPermissions(response);
    }
}
