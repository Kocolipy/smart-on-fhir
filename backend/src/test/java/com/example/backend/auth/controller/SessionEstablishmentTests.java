package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * The session step both Login paths end in, driven with the beans the application wires it from,
 * so what it does to a session is what a signed-in caller's session gets.
 */
class SessionEstablishmentTests {

    private static final UUID USER_ID = UUID.fromString("5b0d6a8e-3c1f-4e7a-9d2b-1f0e8c7a6b54");

    private final Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            "ada", null, AuthorityUtils.createAuthorityList("ROLE_USER"));

    private final AcceptedLogin passwordLogin =
            new AcceptedLogin(
                    authentication, SignedIn.password(USER_ID), List.of("Auditor", "Helpdesk"));

    /**
     * The application's clock, a day after the test's sessions are created: what the session is
     * signed in at is then far from its creation, so the two cannot be mistaken for each other.
     */
    private final Instant signedInAt = Instant.now().plus(Duration.ofDays(1));

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
                csrfTokenRepository,
                Clock.fixed(signedInAt, ZoneOffset.UTC));
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

        HttpSession signedIn =
                establishment.establish(passwordLogin, request, new MockHttpServletResponse());

        MockHttpServletRequest later = new MockHttpServletRequest();
        later.setSession(signedIn);
        assertThat(securityContextRepository.loadDeferredContext(later).get().getAuthentication())
                .isSameAs(authentication);
    }

    /** The rest of the request that signed in runs as the signed-in caller. */
    @Test
    void theCurrentThreadCarriesTheAuthentication() {
        establishment.establish(
                passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    /**
     * The session is indexed by the User's stable id rather than by the name the authentication
     * carries, so a lookup by stable id finds it after a username change.
     */
    @Test
    void theSessionIsIndexedByTheUsersStableId() {
        HttpSession signedIn = establishment.establish(
                passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(signedIn.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo("5b0d6a8e-3c1f-4e7a-9d2b-1f0e8c7a6b54");
    }

    /**
     * The session the step returns is the one it signed in, even where saving the security
     * context made none: a repository that keeps the context off the session (here, on the
     * request) still leaves a session indexed by the User's stable id.
     */
    @Test
    void theReturnedSessionIsSignedInWhereSavingTheContextMadeNoSession() {
        SecurityConfig config = new SecurityConfig();
        SessionEstablishment offTheSession = new SessionEstablishment(
                new RequestAttributeSecurityContextRepository(),
                config.sessionAuthenticationStrategy(),
                config.csrfTokenRepository(),
                Clock.fixed(signedInAt, ZoneOffset.UTC));

        HttpSession signedIn = offTheSession.establish(
                passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(HttpSessionAttributes.signedIn(signedIn).owner()).contains(USER_ID);
    }

    /**
     * The session is signed in at the application clock's instant, which its absolute lifetime
     * counts from: an hour after the Login it is within an 8-hour lifetime, though the session
     * itself was created a day before.
     */
    @Test
    void theSessionIsSignedInAtTheApplicationClocksInstant() {
        HttpSession signedIn = establishment.establish(
                passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(HttpSessionAttributes.signedIn(signedIn).hasOutlived(
                new AbsoluteSessionLifetimePolicy(Duration.ofHours(8)),
                signedInAt.plus(Duration.ofHours(1)))).isFalse();
    }

    /** The session keeps the Groups the Login was accepted with, as its snapshot of them. */
    @Test
    void theSessionKeepsTheGroupsTheLoginWasAcceptedWith() {
        HttpSession signedIn = establishment.establish(
                passwordLogin, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(HttpSessionAttributes.signedIn(signedIn).groups())
                .containsExactly("Auditor", "Helpdesk");
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
                    new AcceptedLogin(
                            authentication, SignedIn.epic(USER_ID, AuditMfaFactor.OTP), List.of()),
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse());

            assertThat(captured.withAction(Level.TRACE, LogEvent.ACTION, "session-start"))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.LOGIN_METHOD, "sso"));
        }
    }
}
