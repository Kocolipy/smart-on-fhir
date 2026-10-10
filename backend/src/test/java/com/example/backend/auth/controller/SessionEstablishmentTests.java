package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * The session step both Login paths end in, driven with the beans the application wires it from,
 * so what it does to a session is what a signed-in caller's session gets.
 */
class SessionEstablishmentTests {

    private static final UUID USER_ID = UUID.fromString("5b0d6a8e-3c1f-4e7a-9d2b-1f0e8c7a6b54");

    private static final String ROLE_MAPPING_HASH = "role-mapping-hash-under-test";

    private final Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            "ada", null, AuthorityUtils.createAuthorityList("ROLE_USER"));

    private final AcceptedLogin passwordLogin =
            new AcceptedLogin(authentication, SignedIn.password(USER_ID), ROLE_MAPPING_HASH);

    private SessionEstablishment establishment;

    private SecurityContextRepository securityContextRepository;

    private CsrfTokenRepository csrfTokenRepository;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        securityContextRepository = config.securityContextRepository();
        csrfTokenRepository = config.csrfTokenRepository();
        establishment = new SessionEstablishment(
                securityContextRepository,
                config.sessionAuthenticationStrategy(),
                csrfTokenRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Session fixation: the session the caller arrived with is renamed, not kept under its id. */
    @Test
    void theSessionTheCallerArrivedWithIsRenamed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String preLoginId = request.getSession().getId();

        establishment.establish(passwordLogin, request, new MockHttpServletResponse());

        assertThat(request.getSession(false).getId()).isNotEqualTo(preLoginId);
    }

    /** A later request on the session is the signed-in caller: the repository loads it back. */
    @Test
    void aLaterRequestOnTheSessionCarriesTheAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        HttpSession signedIn = establishment.establish(passwordLogin, request, new MockHttpServletResponse());

        MockHttpServletRequest later = new MockHttpServletRequest();
        later.setSession(signedIn);
        assertThat(securityContextRepository.loadDeferredContext(later).get().getAuthentication())
                .isSameAs(authentication);
    }

    /** The rest of the request that signed in runs as the signed-in caller. */
    @Test
    void theCurrentThreadCarriesTheAuthentication() {
        establishment.establish(passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    /**
     * The session is indexed by the User's stable id rather than by the name the authentication
     * carries, so a lookup by stable id finds it after a username change.
     */
    @Test
    void theSessionIsIndexedByTheUsersStableId() {
        HttpSession signedIn = establishment.establish(passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(signedIn.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo("5b0d6a8e-3c1f-4e7a-9d2b-1f0e8c7a6b54");
    }

    /** The session records the role mapping its Permissions were resolved under. */
    @Test
    void theSessionRecordsTheRoleMappingHash() {
        HttpSession signedIn = establishment.establish(passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(signedIn.getAttribute(RoleMappingSessions.HASH_ATTRIBUTE))
                .isEqualTo("role-mapping-hash-under-test");
    }

    /** A CSRF token fetched before signing in does not survive into the signed-in session. */
    @Test
    void thePreLoginCsrfTokenIsDropped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);

        establishment.establish(passwordLogin, request, response);

        assertThat(csrfTokenRepository.loadToken(request)).isNull();
    }

    /**
     * One {@code session-start} per established session, naming the User by stable id and
     * carrying the session's idle bound, written under the logger it was written under before
     * the step was extracted from {@link AuthController}.
     */
    @Test
    void oneSessionStartRecordNamesTheUserAndTheSessionsIdleBound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setMaxInactiveInterval(523);

        try (CapturedLog captured = CapturedLog.attach()) {
            establishment.establish(passwordLogin, request, new MockHttpServletResponse());

            assertThat(captured.withAction(Level.TRACE, LogEvent.ACTION, "session-start"))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.getLoggerName())
                                .isEqualTo("com.example.backend.auth.controller.AuthController");
                        assertThat(record.getMDCPropertyMap())
                                .containsEntry(LogContext.USER_ID,
                                        "5b0d6a8e-3c1f-4e7a-9d2b-1f0e8c7a6b54");
                        assertThat(CapturedLog.fields(record))
                                .containsEntry(LogEvent.SESSION_MAX_INACTIVE_INTERVAL, 523);
                    });
        }
    }

    /** D15: the record says how the Login proved who signed in, as the audit trail spells it. */
    @Test
    void theSessionStartRecordCarriesTheLoginMethod() {
        try (CapturedLog captured = CapturedLog.attach()) {
            establishment.establish(
                    new AcceptedLogin(authentication, SignedIn.epic(USER_ID, AuditMfaFactor.OTP),
                            ROLE_MAPPING_HASH),
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse());

            assertThat(captured.withAction(Level.TRACE, LogEvent.ACTION, "session-start"))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.LOGIN_METHOD, "sso"));
        }
    }
}
