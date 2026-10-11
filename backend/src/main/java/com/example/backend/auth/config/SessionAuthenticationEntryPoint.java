package com.example.backend.auth.config;

import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.AccessRefusalLog.Refusal;
import com.example.backend.observability.EndedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * The application chain's answer to a request that needs a session and has none: a bare
 * {@code 401}, and one refusal record saying whether the caller never had a session or
 * brought one that has ended.
 *
 * <p>Nothing else distinguishes the two to the caller — the SPA treats both as "sign in
 * again" — but an operator reading the stream does: a run of {@code session-expired} is
 * users timing out, a run of {@code no-session} is something calling the API cold.
 */
public class SessionAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The logout route, as the chain matches it for the sessionless refusal below. */
    private static final String LOGOUT_PATH = "/api/auth/logout";

    /**
     * What a logout asks the browser to clear. {@code AuthController} sends the same value on
     * the logout it handles; this entry point sends it on the logout it refuses for want of a
     * session, and each side's tests pin the exact value, so the two cannot drift apart
     * unnoticed.
     */
    static final String CLEAR_SITE_DATA_HEADER = "Clear-Site-Data";

    static final String CLEAR_SITE_DATA_ON_LOGOUT = "\"cache\",\"cookies\",\"storage\"";

    private final AccessRefusalLog refusals;

    public SessionAuthenticationEntryPoint(AccessRefusalLog refusals) {
        this.refusals = refusals;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // A logout that arrives with no live session — expired, revoked, or never there — is
        // answered here rather than by AuthController, and is still a browser being signed
        // out: it gets the same Clear-Site-Data the handler sends, so what a dead session left
        // behind is cleared either way.
        if (isLogout(request)) {
            response.setHeader(CLEAR_SITE_DATA_HEADER, CLEAR_SITE_DATA_ON_LOGOUT);
        }
        refusals.record(request, ended(request) ? Refusal.SESSION_EXPIRED : Refusal.NO_SESSION);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * Whether the request brought a session that has ended: one this request's absolute
     * lifetime check just ended, or a session id that names no live session any more — the
     * idle timeout, or a revocation. A request with no session id at all, or with a live
     * session that was simply never signed in to, ended nothing.
     */
    static boolean ended(HttpServletRequest request) {
        if (EndedSession.markedOn(request)) {
            return true;
        }
        return request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid();
    }

    /** The logout operation, which the chain refuses with 401 when no session is live. */
    private static boolean isLogout(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.DELETE.matches(request.getMethod()) && LOGOUT_PATH.equals(path);
    }
}
