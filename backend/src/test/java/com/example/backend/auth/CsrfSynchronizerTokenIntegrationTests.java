package com.example.backend.auth;

import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCookie;
import com.example.backend.SessionCsrf;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The Synchronizer Token Pattern, end to end through the real filter chain and the Redis-backed
 * session repository: what {@code GET /api/auth/csrf} answers, which tokens an unsafe request is
 * refused with, and how login and logout move the token. See
 * {@code /docs/adr/0009-csrf-synchronizer-token.md}.
 *
 * <p>Every token here is obtained the way the SPA obtains it, from the endpoint, and every
 * session is carried as the browser carries it, as the session cookie — so "bound to the
 * session" is asserted against the session store that ships, not a container stand-in.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class CsrfSynchronizerTokenIntegrationTests {

    private static final String CREDENTIALS =
            "{\"username\":\"test-user\",\"password\":\"test-password\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessions;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
    }

    // ---- the endpoint ---------------------------------------------------------------------

    @Test
    void theEndpointReturnsTheTokenInTheBodyUncacheableAndOpensASessionForAGuest()
            throws Exception {
        MvcResult issued = mvc.perform(get(SessionCsrf.PATH)).andReturn();

        assertThat(issued.getResponse().getStatus()).isEqualTo(200);
        assertThat(issued.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        JsonNode body = JSON.readTree(issued.getResponse().getContentAsString());
        assertThat(body.get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(issued.getResponse().getCookie(SessionCookie.NAME))
                .as("the guest's session, which the token is bound to")
                .isNotNull();
    }

    /**
     * The value is XOR-masked afresh per response, so it never repeats (the BREACH defence),
     * while every masking of the one session token is accepted.
     */
    @Test
    void eachFetchMasksTheSameSessionTokenDifferentlyAndEveryMaskingIsAccepted() throws Exception {
        Grant first = fetch(null);
        Grant second = fetch(first.session());

        assertThat(second.session().getValue()).isEqualTo(first.session().getValue());
        assertThat(second.token()).isNotEqualTo(first.token());
        // A guest's logout passes CSRF and is then refused for having no one to log out:
        // 401 means the token was accepted, where a refused token answers 403.
        assertThat(status(logout(first.session(), first))).isEqualTo(401);
        assertThat(status(logout(first.session(), second))).isEqualTo(401);
    }

    // ---- which requests need a token ------------------------------------------------------

    @Test
    void anUnsafeRequestWithNoTokenIsForbidden() throws Exception {
        Grant grant = fetch(null);

        assertThat(status(mvc.perform(post("/api/auth/login").cookie(grant.session())
                .contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS)).andReturn()))
                .isEqualTo(403);
    }

    @Test
    void anUnsafeRequestWithAWrongTokenIsForbidden() throws Exception {
        Grant grant = fetch(null);
        Grant forged = new Grant(grant.session(), grant.headerName(),
                Base64.getUrlEncoder().encodeToString(new byte[72]));

        assertThat(status(login(grant.session(), forged))).isEqualTo(403);
    }

    @Test
    void anUnsafeRequestWithAnotherSessionsTokenIsForbidden() throws Exception {
        Grant mine = fetch(null);
        Grant theirs = fetch(null);
        assertThat(theirs.session().getValue()).isNotEqualTo(mine.session().getValue());

        assertThat(status(login(mine.session(), theirs))).isEqualTo(403);
        assertThat(status(login(mine.session(), mine))).as("with its own token").isEqualTo(200);
    }

    /**
     * No token, and still answered on its merits: with a session, a read succeeds; without
     * one, it is refused {@code 401} by authentication rather than {@code 403} by CSRF.
     */
    @Test
    void safeRequestsNeedNoToken() throws Exception {
        Cookie session = sessionCookie(login(fetch(null)));

        assertThat(status(mvc.perform(get("/api/auth/me").cookie(session)).andReturn()))
                .isEqualTo(200);
        assertThat(status(mvc.perform(head("/api/auth/me").cookie(session)).andReturn()))
                .isEqualTo(200);
        assertThat(status(mvc.perform(get("/")).andReturn())).isEqualTo(200);
        assertThat(status(mvc.perform(get("/api/auth/me")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(head("/api/auth/me")).andReturn())).isEqualTo(401);
    }

    // ---- how the session's life moves the token -------------------------------------------

    /**
     * Login rotates the session id and discards the token the guest session held, so a token
     * fetched before signing in is refused after it, while one fetched afterwards passes.
     */
    @Test
    void aTokenFetchedBeforeLoginIsRejectedAfterLogin() throws Exception {
        Grant beforeLogin = fetch(null);
        Cookie signedIn = sessionCookie(login(beforeLogin));
        assertThat(signedIn.getValue())
                .as("the session id rotated")
                .isNotEqualTo(beforeLogin.session().getValue());

        assertThat(status(logout(signedIn, beforeLogin))).isEqualTo(403);
        assertThat(status(logout(signedIn, fetch(signedIn)))).isEqualTo(204);
    }

    /**
     * A session that expired took its token with it: the logout is refused on CSRF ({@code 403}),
     * and the SPA's single re-fetch-and-retry — the fetch opening a fresh, anonymous session — is
     * then refused for having no one to log out ({@code 401}). Logout is never exempted from CSRF.
     */
    @Test
    void aLogoutOnAnExpiredSessionIsRefused403ThenUnauthorizedOnTheRetry() throws Exception {
        Cookie signedIn = sessionCookie(login(fetch(null)));
        Grant held = fetch(signedIn);
        sessions.deleteById(sessionId(signedIn));

        assertThat(status(logout(signedIn, held))).isEqualTo(403);
        Grant refetched = fetch(signedIn);
        assertThat(status(logout(refetched.session(), refetched))).isEqualTo(401);
    }

    // ---- harness --------------------------------------------------------------------------

    /** A token as the SPA holds it, plus the session cookie it belongs to. */
    private record Grant(Cookie session, String headerName, String token) {
    }

    private Grant fetch(Cookie session) throws Exception {
        MockHttpServletRequestBuilder request = get(SessionCsrf.PATH);
        if (session != null) {
            request.cookie(session);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Cookie issued = result.getResponse().getCookie(SessionCookie.NAME);
        Cookie carried = issued != null ? new Cookie(issued.getName(), issued.getValue()) : session;
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return new Grant(carried, body.get("headerName").asText(), body.get("token").asText());
    }

    private MvcResult login(Grant grant) throws Exception {
        return login(grant.session(), grant);
    }

    private MvcResult login(Cookie session, Grant token) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(session)
                        .header(token.headerName(), token.token())
                        .contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andReturn();
    }

    private MvcResult logout(Cookie session, Grant token) throws Exception {
        return mvc.perform(delete("/api/auth/logout").cookie(session)
                .header(token.headerName(), token.token())).andReturn();
    }

    private static Cookie sessionCookie(MvcResult result) {
        Cookie issued = result.getResponse().getCookie(SessionCookie.NAME);
        assertThat(issued).as("a session cookie on %s", result.getRequest().getRequestURI())
                .isNotNull();
        return new Cookie(issued.getName(), issued.getValue());
    }

    /** Spring Session's cookie carries the session id Base64-encoded. */
    private static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }
}
