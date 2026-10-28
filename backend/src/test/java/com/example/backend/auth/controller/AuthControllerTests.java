package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.SessionCookie;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.auth.LoginHarness;
import com.example.backend.auth.application.CurrentPasswordRejectedException;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.auth.application.PasswordChangeService;
import com.example.backend.auth.application.PasswordPolicyViolationException;
import com.example.backend.auth.application.UnknownSessionIdentityException;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
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

    /** An idle bound no default produces, so a response carrying it read it off the session. */
    private static final int IDLE_TIMEOUT_SECONDS = 523;

    /** The stable id the fixtures' sessions are signed in to: no fixture User's, and no name. */
    private static final UUID SESSION_OWNER =
            UUID.fromString("5e55a0f1-0000-4000-8000-00000000000a");

    private AuthController controller;

    private CsrfTokenRepository csrfTokenRepository;

    /**
     * The login decision, as {@link LoginHarness} assembles it, at a lockout threshold of three.
     * Its one identity store backs the attempt counter AND the credentials the authentication
     * manager checks, which the account aggregate's version of this test could not do: it held an
     * in-memory {@code UserDetailsManager} beside an account repository, so the two could
     * disagree. There is one identity now, so {@link LoginIdentityService} is the
     * {@code UserDetailsService} here, and {@code grace} holds every Permission because she is in
     * the Superuser Group rather than because a fixture said so. The lockout itself is exercised
     * in {@code LoginLockoutTests}.
     */
    private final LoginHarness harness = LoginHarness.builder()
            .clock(Clock.fixed(Instant.parse("2026-09-24T07:00:00Z"), ZoneOffset.UTC))
            .lockoutThreshold(3)
            .build();

    private final InMemoryScimUserRepository users = harness.users();
    private final InMemoryScimGroupRepository groups = harness.groups();
    private final com.example.backend.audit.RecordingAuditTrail audit = harness.audit();

    /** The session registry the login path ends a User's other sessions through. */
    private final com.example.backend.auth.InMemoryAccountSessions accountSessions =
            harness.sessions();

    /** The transaction the login path's after-commit work waits on. */
    private final com.example.backend.auth.PendingCommit transaction = harness.transaction();

    @BeforeEach
    void setUp() {
        SecurityConfig config = harness.config();
        PasswordEncoder passwordEncoder = harness.passwordEncoder();
        users.given(identity("ada", passwordEncoder.encode("correct-password")));
        ScimUser grace = users.given(
                identity("grace", passwordEncoder.encode("another-correct-password")));
        // Authority is a Group membership: she is arranged as a Superuser by putting her in the
        // reserved Admin group under the Superuser Group id the mapping names — through the
        // port, because nothing else may mint a reserved resource.
        groups.createReserved(
                ScimGroup.created(
                        TestGroupPermissions.SUPERUSER_GROUP_ID,
                        "Admins",
                        List.of(ScimGroupMember.reference(grace.id())),
                        Instant.EPOCH),
                ReservedResourceName.ADMIN_GROUP);

        csrfTokenRepository = config.csrfTokenRepository();
        DefaultCookieSerializer cookieSerializer = new DefaultCookieSerializer();
        cookieSerializer.setCookieName(SessionCookie.NAME);
        SessionEnding sessionEnding = new SessionEnding(audit, cookieSerializer);
        controller = new AuthController(
                harness.login(),
                // The same flow against SQL Server and Redis, the security filter chain included, is
                // PasswordChangeLifecycleIntegrationTests; this pins the adapter's own work.
                new PasswordChangeService(
                        users,
                        new InMemoryScimWriteLocks(),
                        new PasswordAcceptance(new InMemoryScimPasswordHistoryRepository(),
                                ScimPasswordAcceptanceConfig.hasher(passwordEncoder)),
                        passwordEncoder,
                        harness.loginState(),
                        harness.revocation(),
                        audit),
                new LoginCompletion(new SessionEstablishment(config.securityContextRepository(),
                        config.sessionAuthenticationStrategy(), csrfTokenRepository),
                        harness.outcomes(), sessionEnding),
                sessionEnding);
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

    /**
     * A member of the Superuser Group signs in holding baseline access and every Permission:
     * authority beyond the baseline is Permissions alone (ADR 0015).
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
        assertThat(response.permissions()).containsExactly(
                "account:unlock", "audit:read", "connector:read", "group:read", "ops:read",
                "patient:read", "user:read");
        assertThat(response.superuser()).isTrue();
        assertThat(savedContext.getAuthentication().isAuthenticated()).isTrue();
        assertThat(savedContext.getAuthentication().getName()).isEqualTo("grace");
        assertThat(savedContext.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER");
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
     * bare {@code 401} — the password analogue of ADR 0012's D24 — so a shared browser is never
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
        MockHttpSession session = session();
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

    /**
     * {@code /me} names the session's User by the stable id its principal index holds — the id
     * every operation on a User compares — never by the principal's name, which a connector can
     * rename to another User's mid-session.
     */
    @Test
    void currentUserReportsTheStableIdTheSessionHoldsRatherThanItsName() {
        AuthController.UserResponse response = controller.currentUser(
                new TestingAuthenticationToken("ada", null, "ROLE_USER"), session());

        assertThat(response.id()).isEqualTo(SESSION_OWNER);
    }

    /** A session that identifies nobody is refused as an anonymous request is. */
    @Test
    void currentUserFromASessionWithNoPrincipalIndexIsRefused() {
        assertThatThrownBy(() -> controller.currentUser(
                        new TestingAuthenticationToken("ada", null, "ROLE_USER"),
                        new MockHttpSession()))
                .isInstanceOf(UnknownSessionIdentityException.class);
    }

    /** Login answers with the body {@code /me} does: the stable id of the User it signed in. */
    @Test
    void loginReportsTheSignedInUsersStableId() {
        AuthController.UserResponse response = controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertThat(response.id()).isEqualTo(users.require("ada").id());
    }

    /**
     * A session signed in to {@link #SESSION_OWNER}, with the idle bound the fixtures below expect
     * in every response.
     */
    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        HttpSessionAttributes.signedIn(session).signIn(SESSION_OWNER);
        session.setMaxInactiveInterval(IDLE_TIMEOUT_SECONDS);
        return session;
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

    /**
     * A User deleted while its password was being compared is refused like every other refusal,
     * never answered as a server fault: its answer is byte for byte a wrong password's.
     */
    @Test
    void aLoginWhoseUserIsDeletedMidwayAnswersExactlyAsAWrongPasswordsDoes() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpServletResponse wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", "wrong-password")))
                .andReturn().getResponse();
        deleteAdaOnceHerNameIsRead();

        MockHttpServletResponse deleted = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ada", "correct-password")))
                .andReturn().getResponse();

        assertThat(List.of(deleted.getStatus(), deleted.getContentAsString(),
                        deleted.getHeaderNames()))
                .isEqualTo(List.of(401, wrongPassword.getContentAsString(),
                        wrongPassword.getHeaderNames()));
    }

    /** It ends the session the browser held, as every refusal does. */
    @Test
    void aLoginWhoseUserIsDeletedMidwayEndsTheSessionTheBrowserHeld() {
        MockHttpSession previous = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(previous);
        deleteAdaOnceHerNameIsRead();

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("ada", "correct-password"),
                request,
                new MockHttpServletResponse()))
                .isInstanceOf(AuthController.LoginRefusedException.class);

        assertThat(previous.isInvalid()).isTrue();
    }

    /** A SCIM {@code DELETE} of Ada landing between the Login's read by name and its decision. */
    private void deleteAdaOnceHerNameIsRead() {
        UUID ada = users.require("ada").id();
        users.afterNextNameLookup(() -> users.deleteById(ada, ScimIdentities.NOW));
    }

    /**
     * The token belonged to the closed session and ended with it; logout writes no
     * replacement anywhere, because the next login fetches one for the session it
     * opens.
     */
    @Test
    void logoutEndsTheCsrfTokenWithTheSession() {
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
        Cookie cleared = response.getCookie(SessionCookie.NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
    }

    /** No session identifies nobody: refused as an anonymous request is, before any comparison. */
    @Test
    void aChangeWithoutASessionIsRefusedAsAnAnonymousRequestIs() {
        assertThatThrownBy(() -> controller.changePassword(
                        new AuthController.ChangePasswordRequest("correct-password", NEW_PASSWORD),
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse()))
                .isInstanceOf(UnknownSessionIdentityException.class);
    }

    @Test
    void aChangeFromASessionWithNoPrincipalIndexIsRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);

        assertThatThrownBy(() -> controller.changePassword(
                        new AuthController.ChangePasswordRequest("correct-password", NEW_PASSWORD),
                        request,
                        new MockHttpServletResponse()))
                .isInstanceOf(UnknownSessionIdentityException.class);
        assertThat(session.isInvalid()).isFalse();
    }

    /**
     * Over MockMvc, because the statuses and both bodies are annotation-driven. The {@code 401}
     * carries the password check's mark, which is how the SPA tells it from the bare {@code 401}
     * a request with no live session gets, refused before anything was compared.
     */
    @Test
    void aRefusedChangeAnswers401MarkedAndABrokenRule400NamingOnlyTheRule() throws Exception {
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
                .andExpect(content().json("{\"refused\":\"current-password\"}", true));

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

    /**
     * A principal index that is not a stable id identifies nobody: the same generic {@code 401} a
     * session with no index gets — no password check's mark — never a {@code 500}, and no
     * credential moves.
     */
    @Test
    void aChangeFromASessionWithAMalformedPrincipalIndexAnswersTheGeneric401() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "not-an-id");
        String hashBefore = users.require("ada").login().passwordHash();

        mvc.perform(post("/api/auth/change-password")
                        .session(session)
                        // JSON whatever the caller accepts, as every 401 on the API is.
                        .accept(MediaType.TEXT_HTML)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\","
                                + "\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(
                        "{\"status\":401,\"code\":\"request-refused\","
                                + "\"detail\":\"The request was refused.\"}", true));
        assertThat(users.require("ada").login().passwordHash()).isEqualTo(hashBefore);
    }

    /**
     * The password check's refusals are one refusal: a locked account's answer — the right current
     * password included — is byte for byte a wrong password's, so the mark tells the session's
     * holder only that the check answered, never why.
     */
    @Test
    void aLockedAccountsChangeRefusalAnswersExactlyAsAWrongPasswordsDoes() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                users.require("ada").id().toString());
        String wrong = "{\"currentPassword\":\"wrong-password\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}";
        MockHttpServletResponse wrongPassword = mvc.perform(post("/api/auth/change-password")
                        .session(session).contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andReturn().getResponse();
        mvc.perform(post("/api/auth/change-password")
                .session(session).contentType(MediaType.APPLICATION_JSON).content(wrong));
        mvc.perform(post("/api/auth/change-password")
                .session(session).contentType(MediaType.APPLICATION_JSON).content(wrong));

        MockHttpServletResponse locked = mvc.perform(post("/api/auth/change-password")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-password\","
                                + "\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn().getResponse();

        assertThat(users.require("ada").login().isLocked()).as("the account is locked").isTrue();
        assertThat(List.of(locked.getStatus(), locked.getContentAsString(), locked.getHeaderNames()))
                .isEqualTo(List.of(401, wrongPassword.getContentAsString(),
                        wrongPassword.getHeaderNames()));
    }

    @Test
    void thePolicyHandlerCarriesTheRuleNameAndDescription() {
        AuthController.PasswordRuleViolation body = controller.passwordPolicyViolated(
                new PasswordPolicyViolationException(PasswordPolicy.Rule.CONTAINS_USER_NAME));

        assertThat(body).isEqualTo(new AuthController.PasswordRuleViolation(
                PasswordPolicy.Rule.CONTAINS_USER_NAME.name(),
                PasswordPolicy.Rule.CONTAINS_USER_NAME.message()));
    }

    /** The session is the Superuser's exactly when it holds {@code ROLE_SUPERUSER}. */
    @Test
    void currentUserReportsASessionHoldingRoleSuperuserAsTheSuperuser() {
        AuthController.UserResponse response = controller.currentUser(
                new TestingAuthenticationToken("grace", null,
                        "ROLE_USER", "ROLE_SUPERUSER", "user:read"),
                session());

        assertThat(response.superuser()).isTrue();
        assertThat(response.permissions()).containsExactly("user:read");
    }

    /**
     * No number of Permissions makes a session the Superuser's, and the Superuser role is never
     * reported as a Permission.
     */
    @Test
    void currentUserReportsASessionHoldingEveryPermissionButNotRoleSuperuserAsNotTheSuperuser() {
        String[] authorities = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("ROLE_USER"),
                        java.util.Arrays.stream(Permission.values()).map(Permission::value))
                .toArray(String[]::new);

        AuthController.UserResponse response = controller.currentUser(
                new TestingAuthenticationToken("grace", null, authorities), session());

        assertThat(response.superuser()).isFalse();
        assertThat(response.permissions()).hasSize(Permission.values().length);
    }

    /** A confined session holds no Permission, so it reports none, and says the change is due. */
    @Test
    void currentUserReportsAConfinedSessionWithNoRole() {
        AuthController.UserResponse response = controller.currentUser(new TestingAuthenticationToken(
                "ada", null, LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY), session());

        assertThat(response).isEqualTo(new AuthController.UserResponse(
                SESSION_OWNER, "ada", List.of(), false, true, IDLE_TIMEOUT_SECONDS));
    }

    @Test
    void currentUserReportsAnUnconfinedSessionAsNotDue() {
        assertThat(controller.currentUser(
                        new TestingAuthenticationToken("ada", null, "ROLE_USER"), session()))
                .isEqualTo(new AuthController.UserResponse(
                        SESSION_OWNER, "ada", List.of(), false, false, IDLE_TIMEOUT_SECONDS));
        assertThat(controller.currentUser(
                        new TestingAuthenticationToken("grace", null, "ROLE_USER", "user:read"),
                        session()))
                .isEqualTo(new AuthController.UserResponse(
                        SESSION_OWNER, "grace", List.of("user:read"), false, false,
                        IDLE_TIMEOUT_SECONDS));
    }

    /**
     * The session's Permissions, sorted by name whatever order the authorities hold them in, and
     * never a role or the confinement marker reported as one.
     */
    @Test
    void currentUserReportsThePermissionAuthoritiesSortedByName() {
        AuthController.UserResponse ordinary = controller.currentUser(
                new TestingAuthenticationToken(
                        "ada", null, "user:read", "ROLE_USER", "audit:read", "account:unlock"),
                session());
        AuthController.UserResponse admin = controller.currentUser(
                new TestingAuthenticationToken(
                        "grace", null, "ROLE_SUPERUSER", "ROLE_USER", "patient:read", "ops:read",
                        "audit:read"),
                session());

        assertThat(ordinary.permissions())
                .containsExactly("account:unlock", "audit:read", "user:read");
        assertThat(admin.permissions()).containsExactly("audit:read", "ops:read", "patient:read");
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

    /** Over MockMvc, so the header is observed on the response as the handler mapping sends it. */
    @Test
    void theLogoutResponseCarriesTheExactClearSiteDataValue() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(delete("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Clear-Site-Data", CLEAR_SITE_DATA));
    }

    // ---- input length limits -----------------------------------------------------------------

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
