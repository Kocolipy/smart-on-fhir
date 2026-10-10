package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The login, logout and change-password hardening, over the real filter chain against a real
 * Postgres and a real, indexed Redis session store — no in-memory session registry.
 *
 * <ul>
 *   <li><b>One concurrent session per User.</b> Two cookie jars log in as the same User: the first
 *       jar's session no longer authenticates, the second's does, and another User's session is
 *       untouched. The same for a login confined by a required password change.
 *   <li><b>Input limits.</b> Over-length Login and change-password fields are a {@code 400}
 *       before any business logic: no audit row, no failure-run increment.
 * </ul>
 *
 * <p>Each cookie jar is a session cookie carried between requests exactly as a browser holds it:
 * the Spring Session filter is in the MockMvc chain, so every request's session is read from and
 * written to Redis.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class LoginSessionHardeningIntegrationTests {

    private static final String PASSWORD = "a-perfectly-good-passphrase";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    private final List<UUID> seeded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
    }

    @AfterEach
    void removeSeededIdentities() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
        seeded.clear();
    }

    // ---- one concurrent session per User -------------------------------------------------------

    @Test
    void aSecondLoginEndsTheFirstSessionAndLeavesAnotherUsersSessionUntouched() throws Exception {
        UUID ada = create("single-session-ada");
        create("single-session-bob");
        Cookie bob = logIn("single-session-bob", PASSWORD);

        Cookie first = logIn("single-session-ada", PASSWORD);
        assertThat(status(get("/api/auth/me"), first)).isEqualTo(200);
        Cookie second = logIn("single-session-ada", PASSWORD);

        assertThat(status(get("/api/auth/me"), first)).isEqualTo(401);
        assertThat(status(get("/api/auth/me"), second)).isEqualTo(200);
        assertThat(sessionRepository.findByPrincipalName(ada.toString()))
                .as("the User holds exactly one session, the second login's")
                .containsOnlyKeys(sessionId(second));

        assertThat(status(get("/api/auth/me"), bob))
                .as("a different User's session is untouched by the second login")
                .isEqualTo(200);
    }

    @Test
    void aSecondConfinedLoginEndsTheFirstConfinedSession() throws Exception {
        UUID ada = create("single-session-confined");
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> users.requirePasswordChange(ada, Instant.now()));
        create("single-session-bystander");
        Cookie bystander = logIn("single-session-bystander", PASSWORD);

        Cookie first = logIn("single-session-confined", PASSWORD);
        assertThat(status(get("/api/auth/me"), first)).isEqualTo(200);
        assertThat(meReportsConfined(first)).isTrue();
        Cookie second = logIn("single-session-confined", PASSWORD);

        assertThat(status(get("/api/auth/me"), first)).isEqualTo(401);
        assertThat(status(get("/api/auth/me"), second)).isEqualTo(200);
        assertThat(meReportsConfined(second)).isTrue();
        assertThat(status(get("/api/auth/me"), bystander)).isEqualTo(200);
    }

    /**
     * Logging in again from the jar that already holds the User's session keeps that jar signed
     * in: the session it arrived with is the one retained, rotated to a fresh id.
     */
    @Test
    void loggingInAgainFromTheSameJarKeepsThatJarSignedIn() throws Exception {
        UUID ada = create("single-session-same-jar");
        Cookie jar = logIn("single-session-same-jar", PASSWORD);

        Cookie again = logIn("single-session-same-jar", PASSWORD, jar);

        assertThat(again.getValue()).isNotEqualTo(jar.getValue());
        assertThat(status(get("/api/auth/me"), again)).isEqualTo(200);
        assertThat(sessionRepository.findByPrincipalName(ada.toString()))
                .containsOnlyKeys(sessionId(again));
    }

    /**
     * With a live session the handler answers {@code 204}; without one — here the very session
     * just logged out, so its cookie now names nothing — the chain answers {@code 401}. Both are a
     * browser being signed out, and both carry the exact value.
     */
    @Test
    void logoutCarriesClearSiteDataWithAndWithoutASession() throws Exception {
        create("single-session-logout");
        Cookie session = logIn("single-session-logout", PASSWORD);

        MvcResult live =
                mvc.perform(withCsrf(delete("/api/auth/logout")).cookie(session)).andReturn();
        MvcResult dead =
                mvc.perform(withCsrf(delete("/api/auth/logout")).cookie(session)).andReturn();
        MvcResult none = mvc.perform(withCsrf(delete("/api/auth/logout"))).andReturn();

        assertThat(live.getResponse().getStatus()).isEqualTo(204);
        assertThat(dead.getResponse().getStatus()).isEqualTo(401);
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        for (MvcResult result : List.of(live, dead, none)) {
            assertThat(result.getResponse().getHeaders("Clear-Site-Data"))
                    .containsExactly("\"cache\",\"cookies\",\"storage\"");
        }
        assertThat(mvc.perform(withCsrf(get("/api/auth/me"))).andReturn()
                        .getResponse().getHeaders("Clear-Site-Data"))
                .as("only a logout clears site data; any other 401 does not")
                .isEmpty();
    }

    // ---- the idle bound the SPA signs out by ---------------------------------------------------

    /**
     * Login and {@code /me} both report the idle timeout the session is actually held to in the
     * Redis store, so the SPA's inactivity sign-out reads the backend's bound rather than a copy
     * that could drift from it.
     */
    @Test
    void loginAndMeReportTheStoredSessionsIdleTimeout() throws Exception {
        create("idle-timeout-ada");
        MvcResult login = mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("idle-timeout-ada", PASSWORD)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        Cookie issued = login.getResponse().getCookie(sessionCookieName);
        assertThat(issued).as("the login issued a session cookie").isNotNull();
        // A MockMvc request cookie re-wrapping the issued one's name and value: it never reaches
        // a browser or a response, so its Secure flag means nothing here.
        // nosemgrep: java.servlets.security.cookie-issecure-false.cookie-issecure-false
        Cookie session = new Cookie(issued.getName(), issued.getValue());

        Session stored = sessionRepository.findById(sessionId(session));
        assertThat(stored).as("the login's session is in the store").isNotNull();
        long storedSeconds = stored.getMaxInactiveInterval().toSeconds();
        assertThat(storedSeconds).as("the store holds a real idle bound").isPositive();

        String me = mvc.perform(withCsrf(get("/api/auth/me")).cookie(session)).andReturn()
                .getResponse().getContentAsString();
        assertThat(idleTimeoutSeconds(login.getResponse().getContentAsString()))
                .isEqualTo(storedSeconds);
        assertThat(idleTimeoutSeconds(me)).isEqualTo(storedSeconds);
    }

    private static long idleTimeoutSeconds(String body) {
        java.util.regex.Matcher field =
                java.util.regex.Pattern.compile("\"idleTimeoutSeconds\":(\\d+)").matcher(body);
        assertThat(field.find()).as("the body carries idleTimeoutSeconds: %s", body).isTrue();
        return Long.parseLong(field.group(1));
    }

    /**
     * The sessionless refusal names the logout by method AND path, measured inside the context
     * path: a GET of the logout path and a DELETE of another path are ordinary {@code 401}s, while
     * the logout under a non-root context path still clears site data.
     */
    @Test
    void onlyTheLogoutOperationsRefusalClearsSiteData() throws Exception {
        assertThat(mvc.perform(withCsrf(get("/api/auth/logout"))).andReturn().getResponse())
                .satisfies(response -> {
                    assertThat(response.getStatus()).isEqualTo(401);
                    assertThat(response.getHeaders("Clear-Site-Data")).isEmpty();
                });
        assertThat(mvc.perform(withCsrf(delete("/api/auth/me"))).andReturn().getResponse())
                .satisfies(response -> {
                    assertThat(response.getStatus()).isEqualTo(401);
                    assertThat(response.getHeaders("Clear-Site-Data")).isEmpty();
                });
        assertThat(mvc.perform(withCsrf(delete("/app/api/auth/logout")).contextPath("/app"))
                        .andReturn().getResponse())
                .satisfies(response -> {
                    assertThat(response.getStatus()).isEqualTo(401);
                    assertThat(response.getHeaders("Clear-Site-Data"))
                            .containsExactly("\"cache\",\"cookies\",\"storage\"");
                });
    }

    // ---- input limits --------------------------------------------------------------------------

    @Test
    void overLengthLoginFieldsAre400WithNoAuditRowAndNoFailureCounted() throws Exception {
        UUID ada = create("limits-login");
        long auditRowsBefore = auditRows();

        for (String body : List.of(
                loginBody("x".repeat(257), PASSWORD),
                loginBody("limits-login", "p".repeat(257)))) {
            assertThat(mvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }

        assertThat(auditRows()).as("no audit row of any kind").isEqualTo(auditRowsBefore);
        assertThat(failedAttempts(ada)).isZero();
    }

    @Test
    void overLengthPasswordChangeFieldsAre400WithNoAuditRowAndNoFailureCounted() throws Exception {
        UUID ada = create("limits-change");
        Cookie session = logIn("limits-change", PASSWORD);
        long auditRowsBefore = auditRows();

        for (String body : List.of(
                changeBody("p".repeat(257), "another-perfectly-good-one"),
                changeBody("not-the-current-password", "n".repeat(257)))) {
            assertThat(mvc.perform(withCsrf(post("/api/auth/change-password"))
                            .cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }

        assertThat(auditRows()).as("no audit row of any kind").isEqualTo(auditRowsBefore);
        assertThat(failedAttempts(ada)).isZero();
        assertThat(status(get("/api/auth/me"), session))
                .as("a refused body leaves the session standing")
                .isEqualTo(200);
    }

    // ---- a refused Login ends the browser's session ----------------------------------------------

    /**
     * The password analogue of ADR 0013's D24: a refused Login ends whatever session the browser
     * held — here another User's — before its bare {@code 401}, so a shared browser is never left
     * signed in as the previous User. The session is gone from the store, not merely signed out,
     * and the refusal mints no session in its place.
     */
    @Test
    void aRefusedLoginEndsTheSessionTheBrowserHeldAndMintsNone() throws Exception {
        create("refused-ends-ada");
        create("refused-ends-bob");
        Cookie bob = logIn("refused-ends-bob", PASSWORD);

        MvcResult refused = mvc.perform(withCsrf(post("/api/auth/login")).cookie(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("refused-ends-ada", "not-the-password")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(refused.getResponse().getContentAsString()).isEmpty();
        assertThat(status(get("/api/auth/me"), bob)).isEqualTo(401);
        assertThat(sessionRepository.findById(sessionId(bob))).isNull();
        Cookie minted = refused.getResponse().getCookie(sessionCookieName);
        assertThat(minted == null || minted.getMaxAge() == 0)
                .as("the refusal sets no live session cookie")
                .isTrue();
    }

    /**
     * The CSRF token ended with the session, so a retry fetches the next session's — as the SPA
     * does, discarding its token on the {@code 401} — and signs in.
     */
    @Test
    void aRetryAfterARefusedLoginSignsInWithTheNextSessionsToken() throws Exception {
        create("refused-retry-ada");
        Cookie signedInBefore = logIn("refused-retry-ada", PASSWORD);
        mvc.perform(withCsrf(post("/api/auth/login")).cookie(signedInBefore)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("refused-retry-ada", "not-the-password")));

        Cookie signedIn = logIn("refused-retry-ada", PASSWORD, signedInBefore);

        assertThat(status(get("/api/auth/me"), signedIn)).isEqualTo(200);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** An active User with {@link #PASSWORD}, written through the real port. */
    private UUID create(String userName) {
        ScimUser created = new TransactionTemplate(transactionManager).execute(status -> users.create(
                ScimUser.created(
                        UUID.randomUUID(),
                        ScimIdentities.profile(userName, true),
                        passwordEncoder.encode(PASSWORD),
                        ScimIdentities.NOW)));
        seeded.add(created.id());
        return created.id();
    }

    private Cookie logIn(String userName, String password) throws Exception {
        return logIn(userName, password, null);
    }

    /** Logs in for real, from the given jar when there is one, and returns the session cookie. */
    private Cookie logIn(String userName, String password, Cookie jar) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(userName, password));
        if (jar != null) {
            request.cookie(jar);
        }
        MvcResult login = mvc.perform(request).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        Cookie session = login.getResponse().getCookie(sessionCookieName);
        assertThat(session).as("the login issued a session cookie").isNotNull();
        return new Cookie(session.getName(), session.getValue());
    }

    /**
     * The id a session cookie names in the store: Spring Session's cookie serializer writes it
     * Base64-encoded.
     */
    private static String sessionId(Cookie session) {
        return new String(
                java.util.Base64.getDecoder().decode(session.getValue()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean meReportsConfined(Cookie session) throws Exception {
        return mvc.perform(withCsrf(get("/api/auth/me")).cookie(session)).andReturn()
                .getResponse().getContentAsString().contains("\"passwordChangeRequired\":true");
    }

    private int status(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return mvc.perform(withCsrf(request).cookie(session)).andReturn().getResponse().getStatus();
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }

    private static String changeBody(String current, String next) {
        return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
    }

    private long auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events", Long.class);
    }

    private int failedAttempts(UUID userId) {
        return jdbc.queryForObject(
                "SELECT failed_login_attempts FROM scim_users WHERE resource_id = ?",
                Integer.class, userId);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}
