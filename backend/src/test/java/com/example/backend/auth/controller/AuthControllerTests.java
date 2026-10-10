package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.CurrentPasswordRejectedException;
import com.example.backend.auth.application.LoginAttemptService;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.auth.application.LoginOutcomeService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.PasswordChangeService;
import com.example.backend.auth.application.PasswordPolicyViolationException;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimUser;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTests {

    /** Mirrors {@code server.servlet.session.cookie.name}. */
    private static final String SESSION_COOKIE = "JSESSIONID";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    /** An idle bound no default produces, so a response carrying it read it off the session. */
    private static final int IDLE_TIMEOUT_SECONDS = 523;

    private AuthController controller;

    private CsrfTokenRepository csrfTokenRepository;

    /** The session registry the login path ends a User's other sessions through. */
    private com.example.backend.auth.InMemoryAccountSessions accountSessions;

    /** The transaction the login path's after-commit work waits on. */
    private com.example.backend.auth.PendingCommit transaction;

    /**
     * The one identity store. It backs the attempt counter AND the credentials the
     * authentication manager checks, which the account aggregate's version of this
     * test could not do: it held an in-memory {@code UserDetailsManager} beside an
     * account repository, so the two could disagree. There is one identity now, so
     * {@link LoginIdentityService} is the {@code UserDetailsService} here, and
     * {@code grace} holds every Permission because she is in the Superuser Group
     * rather than because a fixture said so. The lockout itself is exercised
     * in {@code LoginLockoutTests}.
     */
    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final com.example.backend.audit.RecordingAuditTrail audit =
            new com.example.backend.audit.RecordingAuditTrail();

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        users.given(identity("ada", passwordEncoder.encode("correct-password")));
        ScimUser grace = users.given(
                identity("grace", passwordEncoder.encode("another-correct-password")));
        // Authority is a Group membership: she is arranged as a Superuser by putting her in the
        // reserved Admin group under the Superuser Group id the mapping names — through the
        // port, because nothing else may mint a reserved resource.
        groups.createReserved(
                ScimGroup.created(
                        TestRoleMappings.SUPERUSER_GROUP_ID,
                        "Admins",
                        List.of(ScimGroupMember.reference(grace.id())),
                        Instant.EPOCH),
                ReservedResourceName.ADMIN_GROUP);

        LoginIdentityService identities =
                new LoginIdentityService(users, groups, passwordEncoder, TestRoleMappings.superuserOnly());
        AuthenticationManager manager = config.authenticationManager(identities, passwordEncoder);
        csrfTokenRepository = config.csrfTokenRepository();
        DefaultCookieSerializer cookieSerializer = new DefaultCookieSerializer();
        cookieSerializer.setCookieName(SESSION_COOKIE);
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T07:00:00Z"), ZoneOffset.UTC);
        accountSessions =
                new com.example.backend.auth.InMemoryAccountSessions();
        transaction =
                new com.example.backend.auth.PendingCommit();
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                new SessionRevocationService(
                        accountSessions,
                        transaction,
                        audit,
                        new RecordingOperationalAlerts()),
                new LockoutPolicy(3),
                audit,
                clock);
        LoginOutcomeService outcomes = RecordingLoginCounts.uncounted(attempts, audit);
        controller = new AuthController(
                new LoginService(manager, attempts, identities, outcomes),
                // The same flow against Postgres and Redis, the security filter chain included, is
                // PasswordChangeLifecycleIntegrationTests; this pins the adapter's own work.
                new PasswordChangeService(
                        users,
                        new PasswordAcceptance(new InMemoryScimPasswordHistoryRepository(),
                                ScimPasswordAcceptanceConfig.hasher(passwordEncoder)),
                        passwordEncoder,
                        attempts,
                        new SessionRevocationService(
                                accountSessions,
                                transaction,
                                audit,
                                new RecordingOperationalAlerts()),
                        audit,
                        clock),
                audit,
                new LoginCompletion(new SessionEstablishment(config.securityContextRepository(),
                        config.sessionAuthenticationStrategy(), csrfTokenRepository), outcomes),
                cookieSerializer);
    }

    private static ScimUser identity(String userName, String passwordHash) {
        return ScimUser.created(
                UUID.randomUUID(),
                ScimIdentities.profile(userName, true),
                passwordHash,
                ScimIdentities.NOW);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginStoresAuthenticatedUserInHttpSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuthController.UserResponse response = controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        SecurityContext savedContext = (SecurityContext) request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(response.username()).isEqualTo("ada");
        assertThat(response.passwordChangeRequired()).isFalse();
        assertThat(savedContext.getAuthentication().isAuthenticated()).isTrue();
        assertThat(savedContext.getAuthentication().getName()).isEqualTo("ada");
    }

    /** The session records the hash of the role mapping its Permissions were resolved under. */
    @Test
    void loginRecordsTheRoleMappingHashOnTheSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        assertThat(request.getSession(false)
                        .getAttribute(AuthController.ROLE_MAPPING_HASH_ATTRIBUTE))
                .isEqualTo(TestRoleMappings.superuserOnly().hash());
    }

    /**
     * A member of the Superuser Group signs in holding baseline access and every Permission —
     * and no administrative role, which no longer exists: authority beyond the baseline is
     * Permissions alone (ADR 0010).
     */
    @Test
    void secondaryUserCanLogIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuthController.UserResponse response = controller.login(
                new AuthController.LoginRequest("grace", "another-correct-password"),
                request,
                new MockHttpServletResponse());

        SecurityContext savedContext = (SecurityContext) request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(response.username()).isEqualTo("grace");
        assertThat(response.permissions()).hasSize(Permission.values().length);
        assertThat(savedContext.getAuthentication().isAuthenticated()).isTrue();
        assertThat(savedContext.getAuthentication().getName()).isEqualTo("grace");
        assertThat(savedContext.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void loginRejectsInvalidCredentialsWithoutCreatingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("ada", "wrong-password"),
                request,
                new MockHttpServletResponse()))
                .isInstanceOf(AuthController.LoginRefusedException.class);
        assertThat(request.getSession(false)).isNull();
    }

    /**
     * A refused Login ends whatever session the browser held, whoever it belonged to, before its
     * bare {@code 401} — the password analogue of ADR 0013's D24 — so a shared browser is never
     * left signed in as the previous User after a sign-in that signed nobody in.
     */
    @Test
    void aRefusedLoginEndsTheSessionTheBrowserHeld() {
        MockHttpSession previousUsers = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(previousUsers);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("grace", null, "ROLE_USER"));

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("ada", "wrong-password"),
                request,
                new MockHttpServletResponse()))
                .isInstanceOf(AuthController.LoginRefusedException.class);

        assertThat(previousUsers.isInvalid()).isTrue();
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** Lockout and deactivation end it the same way: every refusal is the one refusal. */
    @Test
    void aRefusedLoginOfADeactivatedAccountEndsTheSessionTheBrowserHeldToo() {
        users.given(ScimUser.created(UUID.randomUUID(), ScimIdentities.profile("gone", false),
                new SecurityConfig().passwordEncoder().encode("correct-password"), ScimIdentities.NOW));
        MockHttpSession previous = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(previous);

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("gone", "correct-password"),
                request,
                new MockHttpServletResponse()))
                .isInstanceOf(AuthController.LoginRefusedException.class);

        assertThat(previous.isInvalid()).isTrue();
    }

    /**
     * The endpoint does not count attempts itself — it authenticates through the
     * login module, which does. Asserted here because routing the endpoint around
     * that module would compile and pass every other test in this class while
     * silently disabling the lockout. What the counting then does with the
     * attempt is LoginLockoutTests' subject.
     */
    @Test
    void loginAuthenticatesThroughTheModuleThatCountsTheAttempt() {
        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("ada", "wrong-password"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse()))
                .isInstanceOf(AuthController.LoginRefusedException.class);

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);
    }

    /**
     * Session fixation protection: a caller that already holds a session gets a
     * new session id once it authenticates, so an id captured before login cannot
     * be replayed against the authenticated session.
     */
    @Test
    void loginRotatesTheSessionIdOfAPreExistingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String preLoginSessionId = request.getSession(true).getId();

        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        assertThat(request.getSession(false).getId()).isNotEqualTo(preLoginSessionId);
    }

    /**
     * Rotation has to happen before the authentication is written down, otherwise
     * the context is saved into the session that is about to be replaced and the
     * caller comes back authenticated as nobody.
     */
    @Test
    void loginKeepsTheAuthenticationInTheRotatedSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);

        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        SecurityContext savedContext = (SecurityContext) request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(savedContext).isNotNull();
        assertThat(savedContext.getAuthentication().getName()).isEqualTo("ada");
    }

    /**
     * A CSRF token obtained before logging in must not survive the privilege
     * change. Rotation alone would not do it: the session id changes but its
     * attributes, the token among them, move to the new id with it.
     */
    @Test
    void loginDiscardsTheCsrfTokenOfThePreLoginSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        csrfTokenRepository.saveToken(
                csrfTokenRepository.generateToken(request), request, response);
        assertThat(csrfTokenRepository.loadToken(request)).as("the arranged token").isNotNull();

        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                response);

        assertThat(csrfTokenRepository.loadToken(request)).isNull();
        assertThat(request.getSession(false).getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("only the token is dropped, not the session it lived in")
                .isNotNull();
        assertThat(response.getCookie(CSRF_COOKIE)).isNull();
    }

    /**
     * The token travels in the body, never a cookie, under the header name the chain
     * reads it from, and is marked uncacheable so no intermediary can hand it to
     * another caller.
     */
    @Test
    void theCsrfEndpointAnswersWithTheHeaderNameAndTokenAndForbidsCaching() {
        ResponseEntity<AuthController.CsrfTokenResponse> answer = controller.csrfToken(
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "masked-token-value"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(answer.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(answer.getBody()).isEqualTo(
                new AuthController.CsrfTokenResponse("X-CSRF-TOKEN", "masked-token-value"));
    }

    /**
     * Saving to the session is not enough: the rest of the request that performed
     * the login also has to see the authentication, which is what publishing it on
     * the {@link SecurityContextHolder} thread-local provides.
     */
    @Test
    void loginPublishesAuthenticationOnTheCurrentThread() {
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        assertThat(current).isNotNull();
        assertThat(current.getName()).isEqualTo("ada");
        assertThat(current.isAuthenticated()).isTrue();
    }

    @Test
    void currentUserReportsThePrincipalAndRole() {
        AuthController.UserResponse response = controller.currentUser(
                new TestingAuthenticationToken("ada", null, "ROLE_USER"), session());

        assertThat(response.username()).isEqualTo("ada");
        assertThat(response.passwordChangeRequired()).isFalse();
    }

    /**
     * The SPA signs an inactive user out by this figure, so it is the session's own idle bound,
     * not a configured copy of it: a session held to a different bound reports that one.
     */
    @Test
    void currentUserReportsTheSessionsOwnIdleTimeout() {
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(437);

        AuthController.UserResponse response = controller.currentUser(
                new TestingAuthenticationToken("ada", null, "ROLE_USER"), session);

        assertThat(response.idleTimeoutSeconds()).isEqualTo(437);
    }

    /** Login answers with the same figure, read off the session the login continues in. */
    @Test
    void loginReportsTheSignedInSessionsIdleTimeout() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setMaxInactiveInterval(611);

        AuthController.UserResponse response = controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        assertThat(response.idleTimeoutSeconds())
                .isEqualTo(611)
                .isEqualTo(request.getSession(false).getMaxInactiveInterval());
    }

    /** A session with the idle bound the fixtures below expect in every response. */
    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(IDLE_TIMEOUT_SECONDS);
        return session;
    }

    /**
     * {@code /me} reports Permissions and nothing else the session holds: not the baseline role,
     * not a leftover {@code ROLE_ADMIN} a session from before the scheme might carry, not another
     * framework authority — and in the order of their names, not the order they were granted.
     */
    @Test
    void currentUserReportsOnlyPermissionsSortedByName() {
        AuthController.UserResponse response = controller.currentUser(
                new TestingAuthenticationToken("grace", null,
                        "user:write", "ROLE_USER", "ROLE_ADMIN", "FACTOR_PASSWORD", "audit:read"),
                session());

        assertThat(response.username()).isEqualTo("grace");
        assertThat(response.permissions()).containsExactly("audit:read", "user:write");
    }

    /**
     * Every refusal leaves through this one handler, so the decision that a locked
     * account looks exactly like a wrong password is really a property of the
     * response it writes: status 401 and no body at all. Asserted over MockMvc
     * because the mapping is annotation-driven — calling the method directly would
     * prove nothing about the status a caller sees.
     */
    @Test
    void aRefusedLoginAnswersWithAnEmptyUnauthorizedResponse() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ada\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    /**
     * Every refusal is the one refusal: a locked account's answer is byte for byte a wrong
     * password's, so the answer cannot tell its reader that the account exists and is locked.
     */
    @Test
    void aLockedAccountsRefusalAnswersExactlyAsAWrongPasswordsDoes() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpServletResponse wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", "wrong-password")))
                .andReturn().getResponse();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("ada", "wrong-password")));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("ada", "wrong-password")));

        MockHttpServletResponse locked = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", "correct-password")))
                .andReturn().getResponse();

        assertThat(users.require("ada").login().isLocked()).as("the account is locked").isTrue();
        assertThat(List.of(locked.getStatus(), locked.getContentAsString(), locked.getHeaderNames()))
                .isEqualTo(List.of(401, wrongPassword.getContentAsString(),
                        wrongPassword.getHeaderNames()));
    }

    @Test
    void logoutInvalidatesTheSessionAndClearsTheSecurityContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session).isNotNull();

        controller.logout(request, new MockHttpServletResponse());

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * Invalidating the session server-side leaves the browser holding a cookie
     * that names a session which no longer exists, so logout expires it.
     */
    @Test
    void logoutExpiresTheSessionCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        Cookie cleared = response.getCookie(SESSION_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
    }

    /**
     * The token belonged to the closed session and ended with it; logout writes no
     * replacement anywhere, because the next login fetches one for the session it
     * opens.
     */
    @Test
    void logoutEndsTheCsrfTokenWithTheSessionAndWritesNoCsrfCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        MockHttpServletResponse arranged = new MockHttpServletResponse();
        csrfTokenRepository.saveToken(
                csrfTokenRepository.generateToken(request), request, arranged);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        assertThat(csrfTokenRepository.loadToken(request)).isNull();
        assertThat(response.getCookie(CSRF_COOKIE)).isNull();
    }

    /**
     * A logout is audited against the account the session belongs to, named by the stable id the
     * principal index holds — never by the username the security context carries.
     */
    @Test
    void logoutRecordsTheLogoutAgainstTheSessionsStableId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        controller.logout(request, new MockHttpServletResponse());

        UUID ada = users.require("ada").id();
        assertThat(audit.of(com.example.backend.audit.domain.AuditOperation.LOGOUT))
                .containsExactly(new com.example.backend.audit.RecordingAuditTrail.Recorded(
                        com.example.backend.audit.domain.AuditOperation.LOGOUT, ada, ada, null));
    }

    /**
     * A session minted before anyone signed in to it has no principal index: nothing was logged
     * out, so nothing is recorded, and the session still ends.
     */
    @Test
    void logoutOfASessionNoOneSignedInToRecordsNothingAndStillEndsIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);

        assertThatNoException()
                .isThrownBy(() -> controller.logout(request, new MockHttpServletResponse()));

        assertThat(audit.recorded()).isEmpty();
        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * Logging out without a session is a no-op rather than a failure, so a caller
     * whose session already expired still gets a clean logout.
     */
    @Test
    void logoutWithoutASessionSucceedsAndStillClearsTheSecurityContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new TestingAuthenticationToken("ada", "correct-password", "ROLE_USER"));
        SecurityContextHolder.setContext(context);

        assertThatNoException()
                .isThrownBy(() -> controller.logout(request, new MockHttpServletResponse()));

        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---- self-service password change ------------------------------------------------------

    private static final String NEW_PASSWORD = "a-brand-new-passphrase";

    /**
     * The User is the one the session's principal index names — which login writes as the stable
     * id — and on success this session is ended here, directly, with its cookie expired. Its CSRF
     * token ends with it, and no replacement is written: the login that must follow fetches one.
     */
    @Test
    void changingThePasswordReplacesTheCredentialOfTheSessionsUserAndEndsTheSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo(users.require("ada").id().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.changePassword(
                new AuthController.ChangePasswordRequest("correct-password", NEW_PASSWORD),
                request,
                response);

        assertThat(new SecurityConfig().passwordEncoder()
                .matches(NEW_PASSWORD, users.require("ada").login().passwordHash())).isTrue();
        assertThat(new SecurityConfig().passwordEncoder()
                .matches("another-correct-password", users.require("grace").login().passwordHash()))
                .as("nobody else's credential moves")
                .isTrue();
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        Cookie cleared = response.getCookie(SESSION_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(response.getCookie(CSRF_COOKIE)).isNull();
    }

    @Test
    void aChangeWithoutASessionIsRefusedAsALoginWouldBe() {
        assertThatThrownBy(() -> controller.changePassword(
                        new AuthController.ChangePasswordRequest("correct-password", NEW_PASSWORD),
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse()))
                .isInstanceOf(CurrentPasswordRejectedException.class);
    }

    @Test
    void aChangeFromASessionWithNoPrincipalIndexIsRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);

        assertThatThrownBy(() -> controller.changePassword(
                        new AuthController.ChangePasswordRequest("correct-password", NEW_PASSWORD),
                        request,
                        new MockHttpServletResponse()))
                .isInstanceOf(CurrentPasswordRejectedException.class);
        assertThat(session.isInvalid()).isFalse();
    }

    /** Over MockMvc, because the statuses and the rule body are annotation-driven. */
    @Test
    void aRefusedChangeAnswers401BareAndABrokenRule400NamingOnlyTheRule() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                users.require("ada").id().toString());

        mvc.perform(post("/api/auth/change-password")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\","
                                + "\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        mvc.perform(post("/api/auth/change-password")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\","
                                + "\"newPassword\":\"short-pass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value(PasswordPolicy.Rule.TOO_SHORT.name()))
                .andExpect(jsonPath("$.message").value(PasswordPolicy.Rule.TOO_SHORT.message()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("short-pass1"))));
    }

    @Test
    void thePolicyHandlerCarriesTheRuleNameAndDescription() {
        AuthController.PasswordRuleViolation body = controller.passwordPolicyViolated(
                new PasswordPolicyViolationException(PasswordPolicy.Rule.CONTAINS_USER_NAME));

        assertThat(body).isEqualTo(new AuthController.PasswordRuleViolation(
                PasswordPolicy.Rule.CONTAINS_USER_NAME.name(),
                PasswordPolicy.Rule.CONTAINS_USER_NAME.message()));
    }

    /** A confined session holds no Permission, so it reports none, and says the change is due. */
    @Test
    void currentUserReportsAConfinedSessionWithNoRole() {
        AuthController.UserResponse response = controller.currentUser(new TestingAuthenticationToken(
                "ada", null, LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY), session());

        assertThat(response).isEqualTo(new AuthController.UserResponse(
                "ada", List.of(), true, IDLE_TIMEOUT_SECONDS));
    }

    @Test
    void currentUserReportsAnUnconfinedSessionAsNotDue() {
        assertThat(controller.currentUser(
                        new TestingAuthenticationToken("ada", null, "ROLE_USER"), session()))
                .isEqualTo(new AuthController.UserResponse(
                        "ada", List.of(), false, IDLE_TIMEOUT_SECONDS));
        assertThat(controller.currentUser(
                        new TestingAuthenticationToken("grace", null, "ROLE_USER", "user:read"),
                        session()))
                .isEqualTo(new AuthController.UserResponse(
                        "grace", List.of("user:read"), false, IDLE_TIMEOUT_SECONDS));
    }

    /**
     * The session's Permissions, sorted by name whatever order the authorities hold them in, and
     * never a role or the confinement marker reported as one.
     */
    @Test
    void currentUserReportsThePermissionAuthoritiesSortedByName() {
        AuthController.UserResponse ordinary = controller.currentUser(
                new TestingAuthenticationToken(
                        "ada", null, "user:write", "ROLE_USER", "audit:read", "user:read"),
                session());
        AuthController.UserResponse admin = controller.currentUser(
                new TestingAuthenticationToken(
                        "grace", null, "ROLE_ADMIN", "ROLE_USER", "ops:read", "counter:read"),
                session());

        assertThat(ordinary.permissions()).containsExactly("audit:read", "user:read", "user:write");
        assertThat(admin.permissions()).containsExactly("counter:read", "ops:read");
    }

    @Test
    void theChangeRequestNeverPrintsEitherPassword() {
        assertThat(new AuthController.ChangePasswordRequest("current-secret", "next-secret"))
                .hasToString("ChangePasswordRequest[redacted]");
    }

    // ---- one concurrent session per User -----------------------------------------------------

    /**
     * The session the caller logs in from is the one the login keeps — identified by the id it is
     * stored under BEFORE rotation renames it, since that is the only id the store knows it by —
     * and every other session of the User ends once the login commits.
     */
    @Test
    void loginKeepsTheCallersOwnSessionAndEndsTheUsersOthersAfterTheCommit() {
        UUID ada = users.require("ada").id();
        UUID grace = users.require("grace").id();
        MockHttpServletRequest request = new MockHttpServletRequest();
        String preLoginSessionId = request.getSession(true).getId();
        accountSessions.open(ada, "ada-elsewhere");
        accountSessions.open(ada, preLoginSessionId);
        accountSessions.open(grace, "grace-elsewhere");

        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        assertThat(accountSessions.sessionsOf(ada))
                .as("nothing ends before the commit")
                .containsExactly("ada-elsewhere", preLoginSessionId);

        transaction.commit();

        assertThat(accountSessions.sessionsOf(ada)).containsExactly(preLoginSessionId);
        assertThat(accountSessions.sessionsOf(grace)).containsExactly("grace-elsewhere");
    }

    @Test
    void loginWithoutASessionEndsEverySessionTheUserAlreadyHeld() {
        UUID ada = users.require("ada").id();
        accountSessions.open(ada, "ada-elsewhere");

        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());
        transaction.commit();

        assertThat(accountSessions.sessionsOf(ada)).isEmpty();
    }

    @Test
    void aRefusedLoginEndsNoSession() {
        UUID ada = users.require("ada").id();
        accountSessions.open(ada, "ada-elsewhere");

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("ada", "wrong-password"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse()))
                .isInstanceOf(AuthController.LoginRefusedException.class);
        transaction.commit();

        assertThat(accountSessions.sessionsOf(ada)).containsExactly("ada-elsewhere");
    }

    // ---- Clear-Site-Data on logout -----------------------------------------------------------

    private static final String CLEAR_SITE_DATA = "\"cache\",\"cookies\",\"storage\"";

    @Test
    void logoutAsksTheBrowserToClearTheSitesData() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        assertThat(response.getHeaders("Clear-Site-Data")).containsExactly(CLEAR_SITE_DATA);
    }

    @Test
    void logoutWithoutASessionStillAsksTheBrowserToClearTheSitesData() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(new MockHttpServletRequest(), response);

        assertThat(response.getHeaders("Clear-Site-Data")).containsExactly(CLEAR_SITE_DATA);
    }

    /** Over MockMvc, so the header is observed on the response as the handler mapping sends it. */
    @Test
    void theLogoutResponseCarriesTheExactClearSiteDataValue() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(delete("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Clear-Site-Data", CLEAR_SITE_DATA));
    }

    // ---- input length limits -----------------------------------------------------------------

    /** The logout is audited against the stable id the session's principal index holds. */
    @Test
    void logoutRecordsTheEventAgainstTheSessionsUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        controller.logout(request, new MockHttpServletResponse());

        assertThat(audit.of(com.example.backend.audit.domain.AuditOperation.LOGOUT))
                .extracting(com.example.backend.audit.RecordingAuditTrail.Recorded::subjectId)
                .containsExactly(users.require("ada").id());
    }

    /** A session never signed in to names no account, so its logout records nothing. */
    @Test
    void logoutOfASessionWithNoPrincipalIndexRecordsNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("unrelated", "value");

        controller.logout(request, new MockHttpServletResponse());

        assertThat(audit.of(com.example.backend.audit.domain.AuditOperation.LOGOUT)).isEmpty();
    }

    /**
     * The operational stream's line for a logout: one INFO {@code user-logout} record, after the
     * audit append, naming the account by the stable id in the logging context — and that id is
     * gone from the context once the record is written.
     */
    @Test
    void logoutWritesOneUserLogoutRecordNamingTheSessionsUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse());

        try (com.example.backend.audit.CapturedLog captured =
                com.example.backend.audit.CapturedLog.attach()) {
            controller.logout(request, new MockHttpServletResponse());

            java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> records = captured.withAction(
                    ch.qos.logback.classic.Level.TRACE, LogEvent.ACTION, "user-logout");
            assertThat(records).hasSize(1);
            ch.qos.logback.classic.spi.ILoggingEvent record = records.getFirst();
            assertThat(record.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(record.getFormattedMessage()).isEqualTo("Logout completed");
            assertThat(com.example.backend.audit.CapturedLog.fields(record))
                    .containsEntry(LogEvent.KIND, "event")
                    .containsEntry(LogEvent.CATEGORY, java.util.List.of("process"))
                    .containsEntry(LogEvent.TYPE, java.util.List.of("user", "end"))
                    .containsEntry(LogEvent.OUTCOME, "success")
                    .doesNotContainKey(LogEvent.LOCAL_ACTION);
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.USER_ID, users.require("ada").id().toString());
            assertThat(record.getFormattedMessage()).doesNotContain("ada");
        }
        assertThat(org.slf4j.MDC.get(LogContext.USER_ID)).as("the scope is closed").isNull();
    }

    /** No account was logged out, so no logout record is written either. */
    @Test
    void logoutOfASessionWithNoPrincipalIndexWritesNoLogoutRecord() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("unrelated", "value");

        try (com.example.backend.audit.CapturedLog captured =
                com.example.backend.audit.CapturedLog.attach()) {
            controller.logout(request, new MockHttpServletResponse());

            assertThat(captured.withAction(
                    ch.qos.logback.classic.Level.TRACE, LogEvent.ACTION, "user-logout")).isEmpty();
        }
    }

    /**
     * A login starts one session, and the operational stream gets one INFO {@code session-start}
     * for it: the recipe's classification, the idle bound read off the session the login
     * continues in, the User by stable id — and neither the pre-login id nor the rotated one.
     */
    @Test
    void loginWritesOneSessionStartRecordCarryingTheSessionsIdleBound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setMaxInactiveInterval(IDLE_TIMEOUT_SECONDS);
        String preLoginId = request.getSession().getId();

        try (com.example.backend.audit.CapturedLog captured =
                com.example.backend.audit.CapturedLog.attach()) {
            controller.login(
                    new AuthController.LoginRequest("ada", "correct-password"),
                    request,
                    new MockHttpServletResponse());

            java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> records = captured.withAction(
                    ch.qos.logback.classic.Level.TRACE, LogEvent.ACTION, "session-start");
            assertThat(records).hasSize(1);
            ch.qos.logback.classic.spi.ILoggingEvent record = records.getFirst();
            assertThat(record.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(record.getFormattedMessage()).isEqualTo("Session started");
            java.util.Map<String, Object> fields = com.example.backend.audit.CapturedLog.fields(record);
            assertThat(fields)
                    .containsEntry(LogEvent.KIND, "event")
                    .containsEntry(LogEvent.CATEGORY, java.util.List.of("process"))
                    .containsEntry(LogEvent.TYPE, java.util.List.of("start"))
                    .containsEntry(LogEvent.OUTCOME, "success")
                    .containsEntry(LogEvent.SEVERITY, "low")
                    .containsEntry(LogEvent.SESSION_MAX_INACTIVE_INTERVAL, IDLE_TIMEOUT_SECONDS)
                    .doesNotContainKey(LogEvent.LOCAL_ACTION);
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.USER_ID, users.require("ada").id().toString());
            String postLoginId = request.getSession(false).getId();
            assertThat(postLoginId).as("the login rotated the id").isNotEqualTo(preLoginId);
            assertThat(fields.values()).extracting(String::valueOf)
                    .noneMatch(value -> value.equals(preLoginId) || value.equals(postLoginId));
        }
        assertThat(org.slf4j.MDC.get(LogContext.USER_ID)).as("the scope is closed").isNull();
    }

    /** A refused login started no session, so it writes no {@code session-start}. */
    @Test
    void aRefusedLoginWritesNoSessionStartRecord() {
        try (com.example.backend.audit.CapturedLog captured =
                com.example.backend.audit.CapturedLog.attach()) {
            assertThatThrownBy(() -> controller.login(
                    new AuthController.LoginRequest("ada", "wrong-password"),
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse()))
                    .isInstanceOf(AuthController.LoginRefusedException.class);

            assertThat(captured.withAction(
                    ch.qos.logback.classic.Level.TRACE, LogEvent.ACTION, "session-start")).isEmpty();
        }
    }

    /** Neither length validator judges a missing value: that is {@code @NotBlank}'s refusal. */
    @Test
    void theLengthValidatorsLeaveAMissingValueToNotBlank() {
        assertThat(new MaxUserNameLength.Validator().isValid(null, null)).isTrue();
        assertThat(new MaxPasswordLength.Validator().isValid(null, null)).isTrue();
    }

    /** A missing field is the length validators' {@code null}: left to {@code @NotBlank}, a 400. */
    @Test
    void aLoginBodyMissingAFieldIsABare400() throws Exception {
        for (String body : java.util.List.of(
                "{\"password\":\"correct-password\"}", "{\"username\":\"ada\"}")) {
            validatingMvc().perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(""));
        }
        assertThat(audit.recorded()).isEmpty();
    }

    /** A supplementary-plane character: one code point, two UTF-16 units. */
    private static final String ASTRAL = "\uD83D\uDD11";

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    private MockMvc validatingMvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String loginBody(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }

    /**
     * Over-length Login fields are refused with the same bare {@code 400} a blank one gets, before
     * the login service runs — no failure counted, nothing audited, no session ended — so an
     * over-length name is indistinguishable from any other malformed body.
     */
    @Test
    void anOverLengthLoginFieldIsTheSameBare400AsABlankOneAndCountsNothing() throws Exception {
        accountSessions.open(users.require("ada").id(), "ada-elsewhere");
        String blank = validatingMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("", "correct-password")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        for (String body : java.util.List.of(
                loginBody(repeat("a", 257), "correct-password"),
                loginBody(repeat(ASTRAL, 257), "correct-password"),
                loginBody("ada", repeat("p", 257)),
                loginBody("ada", repeat(ASTRAL, 257)))) {
            validatingMvc().perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(blank));
        }

        assertThat(blank).isEmpty();
        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(audit.recorded()).isEmpty();
        transaction.commit();
        assertThat(accountSessions.sessionsOf(users.require("ada").id()))
                .containsExactly("ada-elsewhere");
    }

    /**
     * The bounds are the stored ones, counted the way the store counts them: 256 code points is
     * accepted however many UTF-16 units it takes, so a value at the bound reaches the login
     * service and is refused there, as a wrong password, with a {@code 401}.
     */
    @Test
    void loginFieldsAtTheBoundReachTheLoginService() throws Exception {
        validatingMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(repeat(ASTRAL, 256), repeat(ASTRAL, 256))))
                .andExpect(status().isUnauthorized());
        validatingMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", repeat("p", 256))))
                .andExpect(status().isUnauthorized());

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);
    }

    /**
     * A password is measured in its normalized form, as the policy measures it: 257 code points
     * that compose to 256 are within the bound, so no password the policy accepted is ever refused
     * here for its length.
     */
    @Test
    void aPasswordIsMeasuredInItsNormalizedForm() throws Exception {
        // "e" + COMBINING ACUTE ACCENT composes to a single "é" under NFC.
        String decomposed = repeat("p", 255) + "e\u0301";

        validatingMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", decomposed)))
                .andExpect(status().isUnauthorized());
        validatingMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", repeat("p", 256) + "e\u0301")))
                .andExpect(status().isBadRequest());
    }

    /**
     * Over-length change fields are refused before the current password is checked: a {@code 400}
     * with no body, no failure counted toward the lockout, no refusal audited, and the credential
     * unchanged.
     */
    @Test
    void anOverLengthPasswordChangeFieldIs400AndCountsNothing() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                users.require("ada").id().toString());
        String hashBefore = users.require("ada").login().passwordHash();

        for (String body : java.util.List.of(
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(repeat("p", 257), NEW_PASSWORD),
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted("wrong-password", repeat(ASTRAL, 257)))) {
            validatingMvc().perform(post("/api/auth/change-password")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(""));
        }

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(users.require("ada").login().passwordHash()).isEqualTo(hashBefore);
        assertThat(audit.recorded()).isEmpty();
        assertThat(session.isInvalid()).isFalse();
    }
}
