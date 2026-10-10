package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.LoginAttemptService;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.FailedCall;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginOutcome.Unavailable;
import com.example.backend.auth.application.LoginOutcomeService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.SessionHash;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimUser;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * How a Login is completed ({@link LoginCompletion}), by either login method: the login decision,
 * then the session established from it, then its ending recorded, once — or, on a refusal, the
 * browser's session ended. Driven over the real login decision, session step and outcome module,
 * so the order asserted here is the order a caller's Login runs in.
 */
class LoginCompletionTests {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    private static final String PASSWORD = "correct-password";

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final RecordingLoginCounts counts = new RecordingLoginCounts();

    private final InMemoryAccountSessions accountSessions = new InMemoryAccountSessions();

    private final SecurityConfig config = new SecurityConfig();

    private LoginService login;

    private LoginOutcomeService outcomes;

    private LoginCompletion completion;

    private UUID ada;

    @BeforeEach
    void setUp() {
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        ada = users.given(ScimUser.created(UUID.randomUUID(),
                ScimIdentities.profile("ada", true), passwordEncoder.encode(PASSWORD),
                ScimIdentities.NOW)).id();
        LoginIdentityService identities = new LoginIdentityService(
                users, groups, passwordEncoder, TestRoleMappings.superuserOnly());
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                new SessionRevocationService(
                        accountSessions,
                        new PendingCommit(),
                        audit,
                        new RecordingOperationalAlerts()),
                new LockoutPolicy(5),
                audit,
                new MutableClock(NOW));
        outcomes = new LoginOutcomeService(attempts, audit, counts, counts);
        login = new LoginService(config.authenticationManager(identities, passwordEncoder),
                attempts, identities, outcomes);
        completion = completionOver(config.sessionAuthenticationStrategy());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- accepted ---------------------------------------------------------------------------

    /**
     * The ending is recorded after the session is signed in, so it names the session the User
     * goes on to use — rotated, never the pre-login one — by hash, exactly as an Epic Login's.
     */
    @Test
    void anAcceptedLoginIsRecordedOnceNamingTheSessionItSignedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();

        try (CapturedLog captured = CapturedLog.attach()) {
            Optional<LoginCompletion.SignedInSession> signedIn = completion.complete(
                    password(PASSWORD), request, new MockHttpServletResponse());

            String signedInId = signedIn.orElseThrow().session().getId();
            assertThat(accepted(captured))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(signedInId)));
        }
    }

    @Test
    void anAcceptedLoginIsCountedOnceAsASuccessOfItsMethod() {
        completion.complete(
                password(PASSWORD), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(counts.moved()).containsExactly("password:success");
    }

    /** "No success without a session": a Login whose session step fails is not logged a success. */
    @Test
    void aLoginWhoseSessionCannotBeEstablishedRecordsNoSuccess() {
        LoginCompletion failing = completionOver((authentication, request, response) -> {
            throw new SessionAuthenticationException("the session store is unavailable");
        });

        try (CapturedLog captured = CapturedLog.attach()) {
            assertThatThrownBy(() -> failing.complete(password(PASSWORD),
                    new MockHttpServletRequest(), new MockHttpServletResponse()))
                    .isInstanceOf(SessionAuthenticationException.class);

            assertThat(accepted(captured)).isEmpty();
        }
        assertThat(counts.moved()).isEmpty();
    }

    /** The decision is told which session the Login continues in: the one the browser holds. */
    @Test
    void theDecisionIsHandedTheSessionTheBrowserHeld() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String held = request.getSession().getId();
        List<String> handed = new ArrayList<>();

        completion.complete(retained -> {
            handed.add(retained);
            return login.logIn("ada", PASSWORD, retained);
        }, request, new MockHttpServletResponse());

        assertThat(handed).containsExactly(held);
    }

    /** A browser that held no session hands the decision none. */
    @Test
    void theDecisionOfABrowserWithoutASessionIsHandedNone() {
        List<String> handed = new ArrayList<>();

        completion.complete(retained -> {
            handed.add(retained);
            return login.logIn("ada", PASSWORD, retained);
        }, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(handed).containsOnlyNulls().hasSize(1);
    }

    @Test
    void anAcceptedLoginHandsBackTheAuthenticationItSignedIn() {
        Optional<LoginCompletion.SignedInSession> signedIn = completion.complete(
                password(PASSWORD), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(signedIn.orElseThrow().authentication().getName()).isEqualTo("ada");
    }

    /** The session it hands back is the signed-in one, carrying the User's stable id. */
    @Test
    void anAcceptedLoginHandsBackTheSessionItSignedIn() {
        Optional<LoginCompletion.SignedInSession> signedIn = completion.complete(
                password(PASSWORD), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(signedIn.orElseThrow().session()
                .getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo(ada.toString());
    }

    /**
     * The session records the role mapping its authorities were resolved under, so a later change
     * of mapping can tell it is stale.
     */
    @Test
    void anAcceptedLoginsSessionCarriesTheRoleMappingItsAuthoritiesWereResolvedUnder() {
        Optional<LoginCompletion.SignedInSession> signedIn = completion.complete(
                password(PASSWORD), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(signedIn.orElseThrow().session()
                .getAttribute(RoleMappingSessions.HASH_ATTRIBUTE))
                .isEqualTo(TestRoleMappings.superuserOnly().hash());
    }

    /** A login method's own work on the session — Epic's tokens — is handed the signed-in one. */
    @Test
    void theSignedInWorkIsHandedTheSessionTheLoginSignedIn() {
        List<HttpSession> handed = new ArrayList<>();

        Optional<LoginCompletion.SignedInSession> signedIn = completion.complete(password(PASSWORD),
                handed::add, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(handed).containsExactly(signedIn.orElseThrow().session());
    }

    /** That work is part of signing in: a Login whose work fails is not logged a success either. */
    @Test
    void aLoginWhoseSignedInWorkFailsRecordsNoSuccess() {
        try (CapturedLog captured = CapturedLog.attach()) {
            assertThatThrownBy(() -> completion.complete(password(PASSWORD), session -> {
                throw new IllegalStateException("the tokens could not be kept");
            }, new MockHttpServletRequest(), new MockHttpServletResponse()))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(accepted(captured)).isEmpty();
        }
    }

    /** An Epic Login is completed by the same steps, under its own login method (D15). */
    @Test
    void anAcceptedEpicLoginIsSignedInAndRecordedUnderSso() {
        users.given(ScimIdentities.user("eACTIVE"));

        try (CapturedLog captured = CapturedLog.attach()) {
            completion.complete(
                    retained -> login.logInFromEpic("eACTIVE", retained, AuditMfaFactor.OTP),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertThat(captured.withAction(Level.TRACE, LogEvent.ACTION, "session-start"))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.LOGIN_METHOD, "sso"));
        }
        assertThat(counts.moved()).containsExactly("sso:success");
    }

    // ---- refused ----------------------------------------------------------------------------

    /** D24 and its password analogue: a shared browser is not left signed in as the last User. */
    @Test
    void aRefusedLoginEndsTheSessionTheBrowserHeld() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession held = (MockHttpSession) request.getSession();

        completion.complete(password("wrong"), request, new MockHttpServletResponse());

        assertThat(held.isInvalid()).isTrue();
    }

    @Test
    void aRefusedLoginClearsTheSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(previousUser());

        completion.complete(
                password("wrong"), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aRefusedLoginWithoutASessionCreatesNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        completion.complete(password("wrong"), request, new MockHttpServletResponse());

        assertThat(request.getSession(false)).isNull();
    }

    /** Its decision recorded it, counted against the failure run; it is not recorded again. */
    @Test
    void aRefusedLoginIsRecordedOnce() {
        completion.complete(
                password("wrong"), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(counts.moved()).containsExactly("password:refused:BAD_CREDENTIALS");
    }

    @Test
    void aRefusedLoginHandsBackNoSignedInSession() {
        assertThat(completion.complete(
                password("wrong"), new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isEmpty();
    }

    @Test
    void aRefusedLoginRunsNoSignedInWork() {
        List<HttpSession> handed = new ArrayList<>();

        completion.complete(password("wrong"), handed::add, new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertThat(handed).isEmpty();
    }

    // ---- ended before any login decision ------------------------------------------------------

    /** Its records name the session the Login ran in, which it then ends. */
    @Test
    void anUndecidedEndingIsRecordedNamingTheSessionTheBrowserHeld() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String held = request.getSession().getId();

        try (CapturedLog captured = CapturedLog.attach()) {
            completion.endUndecided(EpicRefused.because(EpicLoginFailureReason.INVALID_STATE),
                    request);

            assertThat(captured.withAction(Level.WARN, LogEvent.ACTION, "user-authentication"))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(held)));
        }
    }

    @Test
    void anUndecidedEndingIsRecordedOnce() {
        completion.endUndecided(new Unavailable(new FailedCall("token", ErrorCategory.SERVER, 503)),
                new MockHttpServletRequest());

        assertThat(counts.moved()).containsExactly("failed_call:token:server", "sso:unavailable");
    }

    @Test
    void anUndecidedEndingEndsTheSessionTheBrowserHeld() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession held = (MockHttpSession) request.getSession();

        completion.endUndecided(EpicRefused.because(EpicLoginFailureReason.INVALID_STATE),
                request);

        assertThat(held.isInvalid()).isTrue();
    }

    @Test
    void anUndecidedEndingClearsTheSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(previousUser());

        completion.endUndecided(EpicRefused.because(EpicLoginFailureReason.INVALID_STATE),
                new MockHttpServletRequest());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anUndecidedEndingWithoutASessionCreatesNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        completion.endUndecided(EpicRefused.because(EpicLoginFailureReason.INVALID_STATE),
                request);

        assertThat(request.getSession(false)).isNull();
    }

    /** Signing in takes a decision; an ending that skipped it cannot be one, nor record one. */
    @Test
    void aSignedInLoginCannotEndUndecided() {
        assertThatThrownBy(() -> completion.endUndecided(SignedIn.password(ada),
                new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counts.moved()).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Whoever the browser was signed in as before this Login. */
    private static Authentication previousUser() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "grace", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    /** Ada's password Login with {@code password}, made from whichever session the browser holds. */
    private Function<String, LoginDecision> password(String password) {
        return retained -> login.logIn("ada", password, retained);
    }

    private LoginCompletion completionOver(SessionAuthenticationStrategy rotation) {
        return new LoginCompletion(new SessionEstablishment(config.securityContextRepository(),
                rotation, config.csrfTokenRepository()), outcomes);
    }

    /** Every {@code user-authentication} record of an accepted Login. */
    private static List<ILoggingEvent> accepted(CapturedLog captured) {
        return captured.withAction(Level.INFO, LogEvent.ACTION, "user-authentication");
    }
}
