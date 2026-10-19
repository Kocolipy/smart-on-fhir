package com.example.backend.observability;

import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The one record a request refused for want of authentication ({@code 401}) or of
 * authorization ({@code 403}) gets, from either security chain.
 *
 * <p>The record says THAT access was refused, to whom and to which operation — the caller's
 * {@code user.id} or {@code scim.connector.id} from the logging context, the method, the route
 * template — and why only in a word from {@link Refusal}'s closed set. It never says which
 * rule refused it: no role, no matcher, no authority. A record naming the role a route needs
 * is a map of the authorization rules for whoever reads the log stream. It never carries a
 * credential either, nor any part of one: the reasons are fixed strings, so nothing a caller
 * sent can reach the record through them.
 *
 * <p>A {@code 403} carries the {@code Log_Schema.md} §Error classification of a caller's error —
 * {@code error.code} {@code 403}, {@code error.category} {@code cert/auth}, no follow-up — at
 * {@code WARN}; a {@code 401} carries none.
 *
 * <h2>Once per request</h2>
 *
 * <p>Only on the request dispatch, and only the first time. A refusal answered with
 * {@code sendError} is dispatched again to the error page; should that dispatch ever be refused
 * by a chain too — a filter registered for error dispatches, a rule the error page does not
 * pass — it is the same exchange, not a second refusal, and gets no second record.
 * {@code RefusalLogIntegrationTests} observes one record per refusal over a real socket today;
 * this guard keeps it so if the error dispatch's handling changes.
 * {@code #68}'s request record exists beside this one and is a different record: that one
 * says a request ended, this one says why it was turned away.
 */
public class AccessRefusalLog {

    /** Marks a request already recorded, so a second refusal of the same exchange is not. */
    static final String RECORDED_ATTRIBUTE = AccessRefusalLog.class.getName() + ".recorded";

    private static final Logger log = LoggerFactory.getLogger(AccessRefusalLog.class);

    private final RouteTemplates routes;

    public AccessRefusalLog(RouteTemplates routes) {
        this.routes = routes;
    }

    /**
     * Writes the refusal's record, at {@code WARN}, unless this exchange already has one.
     *
     * @return whether this call wrote it — {@code false} for an exchange already recorded, or a
     *     dispatch other than the request's own — so a caller keeping a record of its own beside
     *     this one keeps it under the same once-per-exchange rule
     */
    public boolean record(HttpServletRequest request, Refusal refusal) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || request.getAttribute(RECORDED_ATTRIBUTE) != null) {
            return false;
        }
        request.setAttribute(RECORDED_ATTRIBUTE, refusal);
        // The published method — one of a fixed set — computed first: it is what the request
        // record names it as, so the two records of one exchange agree.
        String method = RequestIdFilter.method(request);
        LoggingEventBuilder record =
                LogEvent.refused(log, refusal.operation(), Category.PROCESS, Type.ACCESS, Type.DENIED);
        // A 403 is the caller's authorization error, classified as such; a 401 says only that no
        // one was there to authorize.
        if (refusal.status() == HttpServletResponse.SC_FORBIDDEN) {
            LogEvent.withError(record, refusal.status(), ErrorCategory.CERT_AUTH, false);
        }
        record.addKeyValue(LogEvent.REASON, refusal.reason())
                .addKeyValue(LogEvent.HTTP_METHOD, method)
                .addKeyValue(LogEvent.HTTP_ROUTE, routes.of(request))
                .addKeyValue(LogEvent.HTTP_STATUS_CODE, refusal.status())
                .log();
        return true;
    }

    /** Why a request was refused, as generic as the answer the caller itself received. */
    public enum Refusal {
        /** A request without a session, or with one that holds no authentication. */
        NO_SESSION(401, "no-session"),
        /** A request carrying a session that has ended — idle, or past its absolute lifetime. */
        SESSION_EXPIRED(401, "session-expired"),
        /** A SCIM request with no {@code Authorization: Bearer} credential. */
        BEARER_MISSING(401, "bearer-missing"),
        /** A SCIM request whose bearer credential is not accepted, for whatever reason. */
        BEARER_INVALID(401, "bearer-invalid"),
        /**
         * An authenticated caller the authorization rules do not admit to the operation: a
         * Permission it does not hold — a session's on the application chain, a connector token's
         * on the SCIM chain — a route nothing declares, or a session confined by a required
         * password change. One reason for all of them, so the record is no map of which rule
         * refused.
         */
        INSUFFICIENT_PERMISSIONS(403, "insufficient-permissions"),
        /** An unsafe request without the session's CSRF token. Not an authorization decision. */
        CSRF(403, "csrf");

        private final int status;
        private final String reason;

        Refusal(int status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        public int status() {
            return status;
        }

        public String reason() {
            return reason;
        }

        Operation operation() {
            return status == 401 ? Operation.UNAUTHENTICATED : Operation.ACCESS_DENIED;
        }
    }
}
