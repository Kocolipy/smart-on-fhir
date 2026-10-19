package com.example.backend.auth.config;

import com.example.backend.audit.domain.AuditRequest;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.AccessRefusalLog.Refusal;
import com.example.backend.observability.RequestActor;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.observability.RouteTemplates;
import com.example.backend.web.ErrorNamespaces;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * The application chain's {@code 403}, in the request's namespace's error document
 * ({@link ErrorNamespaces}) — the generic {@code request-refused} {@code ApiError} under
 * {@code /api} — after one refusal record.
 *
 * <p>Two refusals reach here and are recorded apart, because they mean different things: an
 * authorization decision turning an authenticated caller away — a Permission it does not hold,
 * a route nothing declares, or a session confined by a required password change — and an
 * unsafe request arriving without its session's CSRF token, which says nothing about what the
 * caller may do. Both come here whichever layer decided: a URL rule in the chain, or a
 * handler's own method-security declaration, whose refusal the app-wide exception handler
 * hands back to the chain rather than answering itself.
 *
 * <p>An authorization refusal is also AUDITED, with the caller, the operation (method and route
 * template) and the one generic reason the log line carries. Neither record names the
 * Permission, Role or rule that refused it: a trail that did would be a map of the
 * authorization policy for whoever reads it, and the missing Permission is recoverable from
 * the operation's declaration in the API document, since each operation requires exactly one.
 * A CSRF refusal is not audited — it is not an authorization decision.
 */
public class RefusalLoggingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessRefusalLog refusals;

    private final AuditTrail audit;

    private final RouteTemplates routes;

    private final ErrorNamespaces namespaces;

    public RefusalLoggingAccessDeniedHandler(AccessRefusalLog refusals, AuditTrail audit,
            RouteTemplates routes, ErrorNamespaces namespaces) {
        this.refusals = refusals;
        this.audit = audit;
        this.routes = routes;
        this.namespaces = namespaces;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException refused) throws IOException {
        if (refused instanceof CsrfException) {
            refusals.record(request, Refusal.CSRF);
        } else if (refusals.record(request, Refusal.INSUFFICIENT_PERMISSIONS)) {
            // Only when the log took it: the same once-per-exchange guard covers both records,
            // so an error dispatch refused again is neither logged nor audited a second time.
            audit.append(new Audited.AccessDenied(
                    RequestActor.userOf(request),
                    new AuditRequest(RequestIdFilter.method(request), routes.of(request),
                            RequestIdFilter.requestId(request))));
        }
        namespaces.refuse(request, response, HttpServletResponse.SC_FORBIDDEN);
    }
}
