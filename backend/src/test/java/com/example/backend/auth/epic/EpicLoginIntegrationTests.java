package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.nimbusds.jose.jwk.JWKSet;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Epic Login's happy path end to end (spec flow steps 1–7, section 7 steps 5 and 6): a clinician
 * whose Practitioner FHIR ID is the {@code userName} of a provisioned User opens the application
 * from Epic and is signed in exactly as password Login would sign them in.
 *
 * <p>Epic is a {@link FakeEpic} started for each test, which verifies our client assertion against
 * our own published JWKS and mints the {@code id_token} the test names. The browser is played by
 * the test: each redirect is followed by hand, our routes through MockMvc over the real filter
 * chain and a real, indexed Redis session store, and Epic's {@code /authorize} over HTTP.
 *
 * <p>The {@code dev} profile lets the fake be plain {@code http} on the loopback interface (D21);
 * nothing else in this context depends on it.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicLoginIntegrationTests {

    private static final String FHIR_BASE = "https://fhir.example.org/api/FHIR/R4";

    private static final String CLIENT_ID = "epic-client-id";

    private static final String REDIRECT_URI = "https://app.example.org/api/auth/epic/callback";

    private static final String ACTIVE_KID = "active-2026-04";

    private static final String PASSWORD = "a-perfectly-good-passphrase";

    /** The Admin group, which the test role mapping maps to the Superuser Role. */
    private static final UUID ADMIN_GROUP = UUID.fromString("00000000-0000-4000-8000-00000000a001");

    /** Every Permission the Superuser Role holds, sorted as {@code /api/auth/me} reports them. */
    private static final List<String> SUPERUSER_PERMISSIONS = List.of(
            "audit:read", "connector:read", "connector:token", "connector:write", "counter:read",
            "counter:write", "group:read", "group:write", "ops:read", "user:read", "user:write");

    private static final KeyPair ACTIVE_KEY = EpicTestKeys.p384KeyPair();

    /** One port for the class, so the issuer the context is configured with names each fake. */
    private static final int EPIC_PORT = freePort();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        registry.add("app.epic.enabled", () -> "true");
        registry.add("app.epic.fhir-base", () -> FHIR_BASE);
        registry.add("app.epic.oauth-issuer", () -> "http://localhost:" + EPIC_PORT + "/oauth2");
        registry.add("app.epic.client-id", () -> CLIENT_ID);
        registry.add("app.epic.redirect-uri", () -> REDIRECT_URI);
        registry.add("app.epic.client-key", () -> EpicTestKeys.pem(ACTIVE_KEY));
        registry.add("app.epic.client-key-id", () -> ACTIVE_KID);
    }

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

    @Autowired
    private RedisConnectionFactory redis;

    @Autowired
    private RoleMapping roleMapping;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private EpicJwks ourJwks;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    private FakeEpic epic;

    private final List<UUID> seeded = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        epic = FakeEpic.start(EPIC_PORT, CLIENT_ID, ACTIVE_KID, this::publishedJwks);
    }

    @AfterEach
    void tearDown() {
        epic.close();
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM scim_group_members WHERE user_id = ?", id);
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
        seeded.clear();
    }

    // ---- the launch and the authorize redirect ------------------------------------------------

    /** Flow step 2: everything Epic's authorization endpoint is sent, and nothing to spare. */
    @Test
    void theAuthorizeRedirectCarriesTheLaunchTheAudienceTheScopeAndPkce() throws Exception {
        URI authorize = launch(null).epicAuthorize();

        Map<String, String> sent = FakeEpic.queryOf(authorize);
        assertThat(authorize.toString()).startsWith(epic.issuer() + "/authorize?");
        assertThat(sent).containsEntry("response_type", "code")
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("scope", "launch openid fhirUser")
                .containsEntry("launch", "launch-context-from-hyperspace")
                .containsEntry("aud", FHIR_BASE)
                .containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");
    }

    // ---- the happy path -----------------------------------------------------------------------

    /** Flow step 7: the browser lands at {@code /}, and the SPA's {@code /me} path takes over. */
    @Test
    void theCallbackLandsAtTheApplicationRoot() throws Exception {
        String practitioner = provision();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getStatus()).isEqualTo(302);
        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    /**
     * The dev profile alone also accepts the relative {@code Practitioner/{id}} the local SMART
     * launcher issues as {@code fhirUser}; this context runs in it.
     */
    @Test
    void inTheDevProfileARelativeFhirUserSignsThePractitionerIn() throws Exception {
        String practitioner = provision();

        Landing landing = completeAs("Practitioner/" + practitioner, launch(null));

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void theSignedInSessionIsTheUsersWithThePermissionsItsGroupsConfer() throws Exception {
        String practitioner = provisionInAdminGroup();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(me(landing.signedIn()))
                .isEqualTo(new Me(practitioner, SUPERUSER_PERMISSIONS));
    }

    /**
     * The authorities themselves — role, baseline and mapped Permissions — not only /me's view.
     * Spring Security's {@code FACTOR_*} authorities are set aside: they record how a credential
     * was proven, which is exactly what differs between the two paths (D15), and an Epic Login
     * must not claim the password factor it never presented.
     */
    @Test
    void anEpicLoginGivesTheSameAuthoritiesAsPasswordLoginForTheSameUser() throws Exception {
        String practitioner = provisionInAdminGroup();
        Set<String> byPassword = grantsOf(logIn(practitioner, null));

        Set<String> byEpic = grantsOf(signInFromEpic(practitioner, null).signedIn());

        assertThat(byEpic).isEqualTo(byPassword);
    }

    @Test
    void theSessionIdIsRotatedBySigningIn() throws Exception {
        String practitioner = provision();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.signedIn().getValue()).isNotEqualTo(landing.launched().getValue());
    }

    @Test
    void theSessionIsIndexedByTheUsersStableId() throws Exception {
        String practitioner = provision();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findByPrincipalName(idOf(practitioner).toString()))
                .containsOnlyKeys(sessionId(landing.signedIn()));
    }

    @Test
    void theSessionRecordsTheRoleMappingItsPermissionsWereResolvedUnder() throws Exception {
        String practitioner = provision();

        Landing landing = signInFromEpic(practitioner, null);

        Object recorded = stored(landing.signedIn()).getAttribute(RoleMappingSessions.HASH_ATTRIBUTE);
        assertThat(recorded).isEqualTo(roleMapping.hash());
    }

    /** A token fetched while the launch was pending is the pre-login one, and is refused after. */
    @Test
    void aCsrfTokenFetchedBeforeTheCallbackIsRefusedAfterIt() throws Exception {
        String practitioner = provision();
        Launched launched = launch(null);
        Map<String, String> preLoginToken = csrfToken(launched.session());

        Landing landing = complete(practitioner, launched);

        int status = mvc.perform(delete("/api/auth/logout").cookie(landing.signedIn())
                        .header(preLoginToken.get("headerName"), preLoginToken.get("token")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    // ---- the login decision, recorded exactly as password Login records it -------------------

    @Test
    void aSuccessfulEpicLoginMovesTheDormancyBasis() throws Exception {
        String practitioner = provision();
        Instant before = Instant.now();

        signInFromEpic(practitioner, null);

        assertThat(users.findById(idOf(practitioner)).orElseThrow().login().lastAuthenticatedAt())
                .isAfterOrEqualTo(before);
    }

    @Test
    void aSuccessfulEpicLoginIsAuditedAsALoginSuccessByMethodSso() throws Exception {
        String practitioner = provision();

        signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList(
                "SELECT login_method FROM audit_events WHERE operation = 'LOGIN_SUCCESS'"
                        + " AND subject_id = ?", String.class, idOf(practitioner)))
                .containsExactly("sso");
    }

    /**
     * One session per User across both Login paths: a password Login's session ends when the same
     * User signs in from Epic, and only the Epic session remains.
     */
    @Test
    void anEpicLoginAfterAPasswordLoginEndsThePasswordSession() throws Exception {
        String practitioner = provision();
        Cookie byPassword = logIn(practitioner, null);

        Cookie byEpic = signInFromEpic(practitioner, null).signedIn();

        assertThat(status(get("/api/auth/me"), byPassword)).isEqualTo(401);
        assertThat(sessionRepository.findByPrincipalName(idOf(practitioner).toString()))
                .containsOnlyKeys(sessionId(byEpic));
    }

    /** D9: every launch is a fresh Login, whoever the browser was signed in as. */
    @Test
    void aLaunchWhileAnotherUsersSessionIsPresentReplacesIt() throws Exception {
        String clinician = provision();
        String colleague = provision();
        Cookie colleaguesSession = logIn(colleague, null);

        Landing landing = signInFromEpic(clinician, colleaguesSession);

        assertThat(status(get("/api/auth/me"), colleaguesSession)).isEqualTo(401);
        assertThat(sessionRepository.findByPrincipalName(idOf(colleague).toString())).isEmpty();
        assertThat(me(landing.signedIn()).username()).isEqualTo(clinician);
    }

    @Test
    void theEpicLoginCounterRecordsASuccess() throws Exception {
        String practitioner = provision();
        double before = successes();

        signInFromEpic(practitioner, null);

        assertThat(successes()).isEqualTo(before + 1);
    }

    /** D8: the access token, the id_token and the launch context are used and dropped. */
    @Test
    void nothingFromEpicsTokenResponseIsStored() throws Exception {
        String practitioner = provision();

        signInFromEpic(practitioner, null);

        assertThat(epic.idTokens).hasSize(1);
        String everythingInRedis = everythingInRedis();
        assertThat(List.of(epic.accessToken, epic.idTokens.getFirst(), epic.patient,
                        epic.encounter))
                .allSatisfy(value -> assertThat(everythingInRedis).doesNotContain(value));
    }

    // ---- the browser --------------------------------------------------------------------------

    /** The launch session, and Epic's authorization URL the backend redirected it to. */
    private record Launched(Cookie session, URI epicAuthorize) {
    }

    /** The callback's answer, the session it signed in, and the launch session before it. */
    private record Landing(MvcResult callback, Cookie signedIn, Cookie launched) {
    }

    /** {@code /api/auth/me}'s account and Permissions. */
    private record Me(String username, List<String> permissions) {
    }

    /** Epic opens the launch URL, from {@code jar} when the browser already holds a session. */
    private Launched launch(Cookie jar) throws Exception {
        MockHttpServletRequestBuilder opened = get("/api/auth/epic/launch")
                .param("iss", FHIR_BASE)
                .param("launch", "launch-context-from-hyperspace");
        if (jar != null) {
            opened.cookie(jar);
        }
        MvcResult launch = mvc.perform(opened).andReturn();
        assertThat(launch.getResponse().getStatus()).as("the launch redirects").isEqualTo(302);
        Cookie session = issuedCookie(launch);
        MvcResult authorize = mvc.perform(
                get(launch.getResponse().getRedirectedUrl()).cookie(session)).andReturn();
        assertThat(authorize.getResponse().getStatus()).as("the authorize hop redirects")
                .isEqualTo(302);
        return new Launched(session, URI.create(authorize.getResponse().getRedirectedUrl()));
    }

    /** The clinician authorizes at Epic as {@code practitioner}, and Epic calls us back. */
    private Landing complete(String practitioner, Launched launched) throws Exception {
        return completeAs(FHIR_BASE + "/Practitioner/" + practitioner, launched);
    }

    /** {@link #complete}, with Epic's {@code id_token} naming the clinician as {@code fhirUser}. */
    private Landing completeAs(String fhirUser, Launched launched) throws Exception {
        epic.signInAs(fhirUser);
        HttpResponse<Void> atEpic = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(launched.epicAuthorize()).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        URI callback = URI.create(atEpic.headers().firstValue("Location").orElseThrow());
        assertThat(callback.getPath()).isEqualTo("/api/auth/epic/callback");
        // Each parameter by itself: a raw query handed to MockMvc as a URI template would be
        // encoded a second time.
        MockHttpServletRequestBuilder redirected =
                get(callback.getPath()).cookie(launched.session());
        FakeEpic.queryOf(callback).forEach(redirected::param);
        MvcResult result = mvc.perform(redirected).andReturn();
        assertThat(epic.tokenRefusals()).as("Epic accepted our token request").isEmpty();
        Cookie signedIn = result.getResponse().getCookie(sessionCookieName);
        return new Landing(result,
                signedIn == null ? null : new Cookie(signedIn.getName(), signedIn.getValue()),
                launched.session());
    }

    private Landing signInFromEpic(String practitioner, Cookie jar) throws Exception {
        return complete(practitioner, launch(jar));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** An active User whose userName is a fresh, mixed-case Practitioner ID, with a password. */
    private String provision() {
        String practitioner = "ePract" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ScimUser created = new TransactionTemplate(transactionManager).execute(status -> users.create(
                ScimUser.created(
                        UUID.randomUUID(),
                        ScimIdentities.profile(practitioner, true),
                        passwordEncoder.encode(PASSWORD),
                        ScimIdentities.NOW)));
        seeded.add(created.id());
        return practitioner;
    }

    /** {@link #provision()}, and a member of the Admin group, so its Permissions are mapped. */
    private String provisionInAdminGroup() {
        String practitioner = provision();
        jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?, ?)",
                ADMIN_GROUP, idOf(practitioner));
        return practitioner;
    }

    private UUID idOf(String userName) {
        return jdbc.queryForObject(
                "SELECT resource_id FROM scim_users WHERE user_name = ?", UUID.class, userName);
    }

    /** A password Login, from {@code jar} when there is one; the signed-in session cookie. */
    private Cookie logIn(String userName, Cookie jar) throws Exception {
        MockHttpServletRequestBuilder request = SessionCsrf.withCsrf(mvc, post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(userName, PASSWORD));
        if (jar != null) {
            request.cookie(jar);
        }
        MvcResult login = mvc.perform(request).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return issuedCookie(login);
    }

    private Cookie issuedCookie(MvcResult result) {
        Cookie issued = result.getResponse().getCookie(sessionCookieName);
        assertThat(issued).as("a session cookie was issued").isNotNull();
        return new Cookie(issued.getName(), issued.getValue());
    }

    private Me me(Cookie session) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me").cookie(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        tools.jackson.databind.JsonNode body = tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(me.getResponse().getContentAsString());
        List<String> permissions = new ArrayList<>();
        body.get("permissions").forEach(permission -> permissions.add(permission.asText()));
        return new Me(body.get("username").asText(), permissions);
    }

    private Map<String, String> csrfToken(Cookie session) throws Exception {
        String body = mvc.perform(get(SessionCsrf.PATH).cookie(session)).andReturn()
                .getResponse().getContentAsString();
        tools.jackson.databind.JsonNode token =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        return Map.of("headerName", token.get("headerName").asText(),
                "token", token.get("token").asText());
    }

    private int status(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return mvc.perform(request.cookie(session)).andReturn().getResponse().getStatus();
    }

    private Session stored(Cookie session) {
        Session stored = sessionRepository.findById(sessionId(session));
        assertThat(stored).as("the session is in the store").isNotNull();
        return stored;
    }

    /** The session's authorities that grant something: every one but the {@code FACTOR_*}. */
    private Set<String> grantsOf(Cookie session) {
        SecurityContext security = stored(session).getAttribute("SPRING_SECURITY_CONTEXT");
        return security.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("FACTOR_"))
                .collect(Collectors.toSet());
    }

    private double successes() {
        Counter counter = meters.find("epic.login").tag("outcome", "success").counter();
        return counter == null ? 0 : counter.count();
    }

    /** Every key and value in the session store, as text: what the store would hand an attacker. */
    private String everythingInRedis() {
        StringBuilder all = new StringBuilder();
        try (RedisConnection connection = redis.getConnection();
                Cursor<byte[]> keys = connection.keyCommands().scan(ScanOptions.NONE)) {
            while (keys.hasNext()) {
                byte[] key = keys.next();
                all.append(new String(key, StandardCharsets.ISO_8859_1)).append('\n');
                DataType type = connection.keyCommands().type(key);
                if (type == DataType.HASH) {
                    connection.hashCommands().hGetAll(key).forEach((field, value) -> all
                            .append(new String(field, StandardCharsets.ISO_8859_1)).append('=')
                            .append(new String(value, StandardCharsets.ISO_8859_1)).append('\n'));
                } else if (type == DataType.STRING) {
                    all.append(new String(connection.stringCommands().get(key),
                            StandardCharsets.ISO_8859_1)).append('\n');
                } else if (type == DataType.SET) {
                    connection.setCommands().sMembers(key).forEach(member -> all
                            .append(new String(member, StandardCharsets.ISO_8859_1)).append('\n'));
                }
            }
        }
        return all.toString();
    }

    private JWKSet publishedJwks() {
        try {
            return JWKSet.parse(ourJwks.document());
        } catch (ParseException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    /** The id a session cookie names in the store: Spring Session writes it Base64-encoded. */
    private static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }

    private static int freePort() {
        // Test-only: binds an ephemeral local port just to learn a free number for the fake
        // Epic, and closes at once. Nothing is ever sent over it, so there is no traffic for
        // TLS to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
