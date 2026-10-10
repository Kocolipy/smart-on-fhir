package com.example.backend.auth.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Severity;
import com.example.backend.observability.LogEvent.Type;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * The session work every Login ends in, once it has decided who signed in: the step that turns
 * the caller's session into a signed-in one. What counts as a successful login is the Login
 * path's own; everything here is the session and CSRF work only a web adapter can do, shared so
 * no Login path can establish a session differently from another.
 *
 * <p>A component over the chain's own repositories, and package-private: its one caller is
 * {@link LoginCompletion}, which runs it between the login decision and recording the Login's
 * ending, so no login method's web adapter can sign a session in without the rest of the Login
 * around it.
 */
@Component
class SessionEstablishment {

    /**
     * The logger {@code session-start} has always been written under. The record moved here from
     * {@link AuthController} unchanged, and its {@code log.logger} field moves with it only if
     * that is chosen deliberately, not as a side effect of the extraction.
     */
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;

    public SessionEstablishment(
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository) {
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /**
     * Signs the caller's session in as the Login {@code accepted}: as its authentication, with
     * the authorities the login resolved; indexed and logged by the User's stable id; recording
     * the hash of the role mapping those authorities were resolved under; and with a
     * {@code session-start} naming its login method (D15).
     *
     * @return the signed-in session, under its rotated id
     */
    public HttpSession establish(
            AcceptedLogin accepted, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = accepted.authentication();
        // Rotate before the context is saved, so the authentication lands in the
        // session the caller will keep using rather than the pre-login one.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Signs the session in to the User's SCIM stable id and the role mapping the
        // Permissions in the security context were resolved under (SignedInSession owns
        // both attributes). The index overrides Spring Session's default principal-index
        // population (which reads Authentication.getName(), i.e. the userName), so
        // AccountSessionsAdapter — and any future stable-id-keyed session lookup — finds
        // this session by an id that survives a later username change.
        // Authentication.getName() itself is untouched: the security context still names
        // the account by username, which is what the Login's response reports.
        //
        // The null guard never fails as wired: SecurityConfig's
        // HttpSessionSecurityContextRepository creates the session when it saves an
        // authenticated context just above, so PIT's "guard always true" mutant
        // survives as equivalent while
        // SessionEstablishmentTests.theSessionIsIndexedByTheUsersStableId asserts the index.
        HttpSession session = request.getSession(false);
        if (session != null) {
            HttpSessionAttributes.signedIn(session)
                    .signIn(accepted.userId(), accepted.roleMappingHash());
        }

        // The session id has just rotated, but its attributes moved with it — the
        // pre-login CSRF token among them. Dropping it here means a token fetched
        // before authentication is refused after it; the SPA fetches a new one.
        csrfTokenRepository.saveToken(null, request, response);

        HttpSession signedIn = request.getSession();
        recordSessionStart(accepted.userId(), accepted.signedIn().method(), signedIn);
        return signedIn;
    }

    /**
     * The operational stream's {@code session-start}: one per session a login signs in.
     *
     * <p>Written here, where the session becomes an authenticated one, rather than on container
     * session creation. The anonymous session {@code GET /api/auth/csrf} mints exists only to
     * hold the token a login submits; it either becomes this session (its id rotated) or idles
     * out unused, so logging its creation would add a record per page load that names no one.
     * Writing it here also means the record can carry the User's stable id, which no
     * creation-time record could.
     *
     * <p>It carries the login method (D15), so the operational stream tells a password Login
     * from an EHR launch without the audit trail.
     *
     * <p>The record names the session by nothing: not its id, nor anything derived from it,
     * because the id is the session's bearer credential.
     */
    private static void recordSessionStart(
            UUID userId, AuditLoginMethod method, HttpSession session) {
        try (LogContext.Scope scope = LogContext.userId(userId)) {
            LogEvent.success(log, Operation.SESSION_START, Category.PROCESS, Type.START)
                    .addKeyValue(LogEvent.SEVERITY, Severity.LOW.value())
                    .addKeyValue(LogEvent.SESSION_MAX_INACTIVE_INTERVAL,
                            session.getMaxInactiveInterval())
                    .addKeyValue(LogEvent.LOGIN_METHOD, method.value())
                    .log();
        }
    }
}
