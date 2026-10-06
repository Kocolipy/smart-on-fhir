package com.example.backend.auth.controller;

import com.example.backend.auth.domain.RoleMappingSessions;
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
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * The session work every Login ends in, once it has decided who signed in: the step that turns
 * the caller's session into a signed-in one. What counts as a successful login is the Login
 * path's own; everything here is the session and CSRF work only a web adapter can do, shared so
 * no Login path can establish a session differently from another.
 */
public class SessionEstablishment {

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
     * Signs the caller's session in as {@code authentication}.
     *
     * @param authentication  the authenticated User, with the authorities the login resolved
     * @param userId          the User's stable id, which the session is indexed and logged by
     * @param roleMappingHash the hash of the role mapping those authorities were resolved under
     * @return the signed-in session, under its rotated id
     */
    public HttpSession establish(
            Authentication authentication,
            UUID userId,
            String roleMappingHash,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Rotate before the context is saved, so the authentication lands in the
        // session the caller will keep using rather than the pre-login one.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Overrides Spring Session's default principal-index population (which
        // reads Authentication.getName(), i.e. the userName) with the SCIM
        // stable id, so AccountSessionsAdapter — and any future stable-id-keyed
        // session lookup — finds this session by an id that survives a later
        // username change. Authentication.getName() itself is untouched: the
        // security context still names the account by username, which is what
        // the Login's response reports.
        //
        // The null guard never fails as wired: SecurityConfig's
        // HttpSessionSecurityContextRepository creates the session when it saves an
        // authenticated context just above, so PIT's "guard always true" mutant
        // survives as equivalent while
        // SessionEstablishmentTests.theSessionIsIndexedByTheUsersStableId asserts the index.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(
                    FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                    userId.toString());
            // The mapping the Permissions in the security context were resolved under, so a
            // session minted under a different mapping can be told apart from one minted under
            // the running one.
            session.setAttribute(RoleMappingSessions.HASH_ATTRIBUTE, roleMappingHash);
        }

        // The session id has just rotated, but its attributes moved with it — the
        // pre-login CSRF token among them. Dropping it here means a token fetched
        // before authentication is refused after it; the SPA fetches a new one.
        csrfTokenRepository.saveToken(null, request, response);

        HttpSession signedIn = request.getSession();
        recordSessionStart(userId, signedIn);
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
     * <p>The record names the session by nothing: not its id, nor anything derived from it,
     * because the id is the session's bearer credential.
     */
    private static void recordSessionStart(UUID userId, HttpSession session) {
        try (LogContext.Scope scope = LogContext.userId(userId)) {
            LogEvent.success(log, Operation.SESSION_START, Category.PROCESS, Type.START)
                    .addKeyValue(LogEvent.SEVERITY, Severity.LOW.value())
                    .addKeyValue(LogEvent.SESSION_MAX_INACTIVE_INTERVAL,
                            session.getMaxInactiveInterval())
                    .log();
        }
    }
}
