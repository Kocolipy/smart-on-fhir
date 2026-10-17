package com.example.backend.auth.controller;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.stereotype.Component;

/**
 * How the backend ends the caller's own session, whatever the reason: the one place the steps run,
 * in one order, so no caller can drop one of them.
 *
 * <ol>
 *   <li><b>Record</b> the ending, where the {@link Reason} has a record of its own — before the
 *       session is invalidated, because the principal index on the session is the only place the
 *       account's stable id is. Fail-closed: an ending this service cannot account for throws and
 *       leaves the session standing rather than ending it silently.
 *   <li><b>Invalidate</b> the session the request carries, if it carries one. None is created.
 *   <li><b>Clear the security context</b>, so the rest of the request does not run as the User.
 *   <li><b>Expire the session cookie</b>, so the browser's next request arrives with no session id
 *       at all rather than one that now names nothing.
 *   <li><b>{@code Clear-Site-Data}</b>, where the reason calls for it.
 * </ol>
 *
 * <p>The CSRF token is bound to the session, so it ends in step 2 with no work of its own here
 * (ADR 0009). The Session revocation of a User's other sessions is a different capability, owned
 * by {@code SessionRevocationService}; this ends only the session the request carries. A web
 * adapter, not an application service, because ending a session is servlet work (ADR 0001).
 */
@Component
class SessionEnding {

    /** The response header a Logout asks the browser to clear the origin's data with. */
    static final String CLEAR_SITE_DATA_HEADER = "Clear-Site-Data";

    /** Every data type a signed-out session may have left in the browser. */
    static final String CLEAR_SITE_DATA_ON_LOGOUT = "\"cache\",\"cookies\",\"storage\"";

    private static final Logger log = LoggerFactory.getLogger(SessionEnding.class);

    /**
     * Why the caller's session ends, and with it whether the ending takes the optional steps: the
     * Logout record and {@code Clear-Site-Data} go together, because only a sign-out has either.
     * Split the flag when a reason needs one without the other.
     */
    enum Reason {

        /**
         * The User signed out. Recorded as {@code LOGOUT} in the audit trail and one
         * {@code user-logout} line, naming the account the session belongs to; a session that
         * identifies nobody records nothing. Sends {@code Clear-Site-Data} whether or not a live
         * session arrived, so a caller whose session already expired is cleaned up the same way.
         */
        LOGOUT(true),

        /**
         * The User's password changed. The change records its own events, and every session of
         * the User is revoked after its commit; this one is also ended here, directly, so the
         * servlet container's copy cannot be written back when the request completes.
         */
        PASSWORD_CHANGED(false),

        /**
         * A Login ended without signing anyone in — refused by its decision, or failed before one
         * — so whatever session the browser held, whoever it belonged to, ends (ADR 0001, step 5;
         * ADR 0012, D24). Its outcome is recorded by {@code LoginOutcomeService}. No
         * {@code Clear-Site-Data}: the SPA answers a refused Login on the login page it is on.
         */
        LOGIN_SIGNED_NOBODY_IN(false);

        /** Whether this is a sign-out: recorded as a Logout, and answered with Clear-Site-Data. */
        private final boolean signsOut;

        Reason(boolean signsOut) {
            this.signsOut = signsOut;
        }
    }

    private final AuditTrail audit;

    private final CookieSerializer cookieSerializer;

    SessionEnding(AuditTrail audit, CookieSerializer cookieSerializer) {
        this.audit = audit;
        this.cookieSerializer = cookieSerializer;
    }

    /** Ends the session {@code request} carries, for {@code reason}. */
    void end(Reason reason, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            if (reason.signsOut) {
                recordLogout(session);
            }
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        cookieSerializer.writeCookieValue(new CookieValue(request, response, ""));
        if (reason.signsOut) {
            response.setHeader(CLEAR_SITE_DATA_HEADER, CLEAR_SITE_DATA_ON_LOGOUT);
        }
    }

    /**
     * Records the Logout against the account the session belongs to, named by the stable id the
     * principal index holds — which an event may name — never by the username the security
     * context carries, which it may not. A session that identifies nobody has no account to name,
     * and nothing was logged out, so nothing is recorded rather than an event with an invented
     * subject.
     */
    private void recordLogout(HttpSession session) {
        HttpSessionAttributes.signedIn(session).owner().ifPresent(userId -> {
            audit.append(new Audited.Logout(userId));
            // Beside the audit append, after it succeeded: the trail is the record of who logged
            // out, and this is the operational stream's line for the same moment.
            try (LogContext.Scope scope = LogContext.userId(userId)) {
                LogEvent.success(log, Operation.LOGOUT, Category.PROCESS, Type.USER, Type.END)
                        .log();
            }
        });
    }
}
