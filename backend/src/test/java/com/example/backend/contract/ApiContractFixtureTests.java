package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.backend.SessionCsrf;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.epic.EpicTestKeys;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract check for the session-authenticated application API and the actuator endpoints:
 * every documented operation driven to every status {@code docs/openapi.yaml} documents for it,
 * each response held against the document as it returns.
 *
 * <p>The SCIM namespace has its own suite, {@link ScimConformanceFixtureTests}, because there the
 * fixtures are also RFC conformance claims. Here they are only contract claims, so each fixture is
 * one operation reaching one documented outcome over the real filter chain, the real Redis session
 * store and real CSRF tokens, and the closing check fails for any documented status no fixture
 * produced.
 *
 * <p>Users are provisioned fresh over SCIM for each fixture that needs one and removed after it, so
 * nothing here depends on, or disturbs, the seeded Users other suites share. The Bootstrap Admin is
 * used for administrative calls and only ever logged in successfully, so its failure run is never
 * advanced.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
@TestPropertySource(properties = "app.scim.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiContractFixtureTests {

    private static final String ADMIN = "test-admin";

    private static final String ADMIN_PASSWORD = "test-admin-password";

    private static final String CONNECTOR_PASSWORD = "connector-set-pass-1";

    private static final String OWN_PASSWORD = "own-chosen-passphrase-1";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    /** Documented statuses no fixture can produce here, each with the reason. */
    private static final Set<String> NOT_PRODUCIBLE = Set.of(
            // Needs a dependency to be DOWN; this context's Postgres and Redis are up by design.
            "GET /actuator/health 503",
            // Needs Epic Login OFF; this context has it on, to serve the JWKS. The switch-off 404
            // is EpicReleaseGateIntegrationTests', in a context with no Epic variable at all.
            "GET /api/auth/epic/jwks.json 404");

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    private static final ContractRecorder RECORDER = new ContractRecorder(CONTRACT);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Epic Login on, so its public JWKS is served; with generated keys, never committed ones. */
    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EpicTestKeys.epicLoginOn(registry, EpicTestKeys::p384Pem, EpicTestKeys::p384Pem);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    private String writeToken;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter,
                        springSecurityFilterChain)
                .build();
        UUID connector = connectors.create("api-contract", ADMIN).id();
        writeToken = connectors.issueToken(connector, TokenPermissions.ALL, null, ADMIN, TokenPermissions.ALL)
                .presentedValue();
    }

    @AfterEach
    void removeWhatThisFixtureCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @Order(1)
    void fixture(Fixture fixture) throws Exception {
        try {
            fixture.step().run(this);
        } catch (AssertionError | RuntimeException failed) {
            throw new AssertionError("[" + fixture.name() + "] " + failed.getMessage(), failed);
        }
    }

    @Test
    @Order(2)
    void every_documented_application_status_is_produced_by_some_fixture() {
        assertThat(RECORDER.covered())
                .as("the fixtures ran in this JVM before this check — run the whole class")
                .isNotEmpty();
        List<String> uncovered = new ArrayList<>(RECORDER.uncovered("/api"));
        uncovered.addAll(RECORDER.uncovered("/actuator"));
        uncovered.removeAll(NOT_PRODUCIBLE);
        assertThat(uncovered)
                .as("statuses docs/openapi.yaml documents that no fixture produced")
                .isEmpty();
    }

    record Fixture(String name, Step step) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Step {
        void run(ApiContractFixtureTests t) throws Exception;
    }

    private static void add(List<Fixture> all, String name, Step step) {
        all.add(new Fixture(name, step));
    }

    static Stream<Arguments> fixtures() {
        List<Fixture> all = new ArrayList<>();
        authentication(all);
        self(all);
        sessionAndCounter(all);
        administration(all);
        connectors(all);
        audit(all);
        actuator(all);
        return all.stream().map(fixture -> Arguments.of(Named.of(fixture.name(), fixture)));
    }

    // ---- /api/auth --------------------------------------------------------------------

    private static void authentication(List<Fixture> all) {
        add(all, "epic jwks: 200 to a caller with no session, the active then the next key", t -> {
            JsonNode keys = json(t.expect(t.get("/api/auth/epic/jwks.json"), 200)).get("keys");
            assertThat(List.of(keys.get(0).get("kid").asText(), keys.get(1).get("kid").asText()))
                    .containsExactly("active-2026-04", "next-2026-10");
        });
        add(all, "csrf: 200 with the session's token in the body, never cached", t -> {
            MvcResult issued = t.expect(t.get(SessionCsrf.PATH), 200);
            assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("no-store");
            JsonNode body = json(issued);
            assertThat(body.get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
            assertThat(body.get("token").asText()).isNotBlank();
        });
        add(all, "login: 200 with the session and no CSRF cookie", t -> {
            MvcResult login = t.expect(t.csrf(t.post("/api/auth/login"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials(ADMIN, ADMIN_PASSWORD)), 200);
            assertThat(login.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .anyMatch(cookie -> cookie.startsWith("SESSION="))
                    .noneMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
        });
        add(all, "login: 400 for a body that fails validation", t ->
                t.expect(t.csrf(t.post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"), 400));
        add(all, "login: 401 with no body for a wrong password", t -> {
            String userName = t.provision(true);
            MvcResult refused = t.expect(t.csrf(t.post("/api/auth/login"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials(userName, "not-the-password-1")), 401);
            assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
        });
        add(all, "login: 403 without a CSRF token", t ->
                t.expect(t.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(ADMIN, ADMIN_PASSWORD)), 403));

        add(all, "me: 200 for a session, 401 for none", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            JsonNode me = json(t.expect(t.get("/api/auth/me").cookie(admin), 200));
            assertThat(me.get("username").asText()).isEqualTo(ADMIN);
            t.expect(t.get("/api/auth/me"), 401);
        });

        add(all, "logout: 204, 401 without a session, 403 without CSRF", t -> {
            Cookie session = t.logIn(ADMIN, ADMIN_PASSWORD);
            t.expect(t.call(HttpMethod.DELETE, "/api/auth/logout").cookie(session), 403);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, "/api/auth/logout")).cookie(session),
                    204);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, "/api/auth/logout")), 401);
        });

        add(all, "change-password: 204, then the old password no longer logs in", t -> {
            String userName = t.provision(true);
            Cookie confined = t.logIn(userName, CONNECTOR_PASSWORD);
            t.expect(t.csrf(t.post("/api/auth/change-password")).cookie(confined)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(change(CONNECTOR_PASSWORD, OWN_PASSWORD)), 204);
            t.logIn(userName, OWN_PASSWORD);
        });
        add(all, "change-password: 400 naming the broken rule", t -> {
            String userName = t.provision(true);
            Cookie confined = t.logIn(userName, CONNECTOR_PASSWORD);
            JsonNode refused = json(t.expect(t.csrf(t.post("/api/auth/change-password"))
                    .cookie(confined).contentType(MediaType.APPLICATION_JSON)
                    .content(change(CONNECTOR_PASSWORD, "short")), 400));
            assertThat(refused.toString()).doesNotContain("short\"");
        });
        add(all, "change-password: 400 ApiError for a body that fails validation", t -> {
            String userName = t.provision(true);
            Cookie confined = t.logIn(userName, CONNECTOR_PASSWORD);
            JsonNode refused = json(t.expect(t.csrf(t.post("/api/auth/change-password"))
                    .cookie(confined).contentType(MediaType.APPLICATION_JSON)
                    .content("{}"), 400));
            assertThat(refused.path("code").asText()).isEqualTo("invalid-request");
        });
        add(all, "change-password: 401 for a wrong current password, 403 without CSRF", t -> {
            String userName = t.provision(true);
            Cookie confined = t.logIn(userName, CONNECTOR_PASSWORD);
            t.expect(t.csrf(t.post("/api/auth/change-password")).cookie(confined)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(change("wrong-current-pass-1", OWN_PASSWORD)), 401);
            t.expect(t.post("/api/auth/change-password").cookie(confined)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(change(CONNECTOR_PASSWORD, OWN_PASSWORD)), 403);
        });
    }

    // ---- /api/self --------------------------------------------------------------------

    private static void self(List<Fixture> all) {
        add(all, "self: 200 for a settled User, 401 anonymously, 403 while confined", t -> {
            String settled = t.provision(true);
            Cookie session = t.settle(settled);
            JsonNode record = json(t.expect(t.get("/api/self").cookie(session), 200));
            assertThat(record.get("userName").asText()).isEqualTo(settled);
            t.expect(t.get("/api/self"), 401);
            String confined = t.provision(true);
            t.expect(t.get("/api/self").cookie(t.logIn(confined, CONNECTOR_PASSWORD)), 403);
        });
    }

    // ---- /api/session, /api/count -----------------------------------------------------

    private static void sessionAndCounter(List<Fixture> all) {
        add(all, "session: GET, PUT and DELETE, with their refusals", t -> {
            Cookie session = t.settle(t.provision(true));
            t.expect(t.get("/api/session").cookie(session), 200);
            t.expect(t.get("/api/session"), 401);
            t.expect(t.csrf(t.call(HttpMethod.PUT, "/api/session")).cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Contract\"}"), 200);
            t.expect(t.csrf(t.call(HttpMethod.PUT, "/api/session")).cookie(session)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\" \"}"),
                    400);
            t.expect(t.call(HttpMethod.PUT, "/api/session").cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Contract\"}"), 403);
            t.expect(t.csrf(t.call(HttpMethod.PUT, "/api/session"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Contract\"}"), 401);
            t.expect(t.call(HttpMethod.DELETE, "/api/session").cookie(session), 403);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, "/api/session")).cookie(session), 204);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, "/api/session")), 401);
        });
        add(all, "count: GET, increment and reset, with their refusals", t -> {
            // counter:read and counter:write are baseline: every active User holds them, the
            // Superuser and a User in no mapped Group alike. Only a confined session lacks them.
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            Cookie user = t.settle(t.provision(true));
            Cookie confined = t.logIn(t.provision(true), CONNECTOR_PASSWORD);
            t.expect(t.get("/api/count").cookie(admin), 200);
            t.expect(t.get("/api/count").cookie(user), 200);
            t.expect(t.get("/api/count").cookie(confined), 403);
            t.expect(t.get("/api/count"), 401);
            for (String path : List.of("/api/count/increment", "/api/count/reset")) {
                t.expect(t.csrf(t.post(path)).cookie(admin), 200);
                t.expect(t.csrf(t.post(path)).cookie(user), 200);
                t.expect(t.csrf(t.post(path)).cookie(confined), 403);
                t.expect(t.post(path).cookie(admin), 403);
                t.expect(t.csrf(t.post(path)), 401);
            }
        });
    }

    // ---- /api/admin/accounts, /api/admin/groups ---------------------------------------

    private static void administration(List<Fixture> all) {
        add(all, "accounts, groups and roles listings: 200 for an Admin, 403 for a User, 401 for none",
                t -> {
                    Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
                    Cookie user = t.settle(t.provision(true));
                    for (String path : List.of(
                            "/api/admin/accounts", "/api/admin/groups", "/api/admin/roles")) {
                        t.expect(t.get(path).cookie(admin), 200);
                        t.expect(t.get(path).cookie(user), 403);
                        t.expect(t.get(path), 401);
                    }
                });
        add(all, "unlock: every documented outcome", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            String target = t.provision(true);
            UUID id = t.userId(target);
            Cookie user = t.settle(t.provision(true));
            t.expect(t.csrf(t.post("/api/admin/accounts/" + id + "/unlock")).cookie(admin), 200);
            t.expect(t.csrf(t.post("/api/admin/accounts/" + id + "/unlock")), 401);
            t.expect(t.csrf(t.post("/api/admin/accounts/" + id + "/unlock")).cookie(user), 403);
            t.expect(t.csrf(t.post("/api/admin/accounts/not-a-uuid/unlock")).cookie(admin), 400);
            t.expect(t.csrf(t.post("/api/admin/accounts/" + UUID.randomUUID() + "/unlock"))
                    .cookie(admin), 404);
        });
        add(all, "force-password-change: every documented outcome", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            UUID credentialed = t.userId(t.provision(true));
            UUID credentialless = t.userId(t.provision(false));
            Cookie user = t.settle(t.provision(true));
            String path = "/api/admin/accounts/%s/force-password-change";
            t.expect(t.csrf(t.post(path.formatted(credentialed))).cookie(admin), 200);
            t.expect(t.csrf(t.post(path.formatted(credentialed))), 401);
            t.expect(t.csrf(t.post(path.formatted(credentialed))).cookie(user), 403);
            t.expect(t.csrf(t.post(path.formatted("not-a-uuid"))).cookie(admin), 400);
            t.expect(t.csrf(t.post(path.formatted(UUID.randomUUID()))).cookie(admin), 404);
            t.expect(t.csrf(t.post(path.formatted(credentialless))).cookie(admin), 409);
        });
    }

    // ---- /api/admin/connectors --------------------------------------------------------

    private static void connectors(List<Fixture> all) {
        add(all, "connectors: the whole lifecycle and every documented refusal", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            Cookie user = t.settle(t.provision(true));
            String base = "/api/admin/connectors";

            t.expect(t.get(base).cookie(admin), 200);
            t.expect(t.get(base).cookie(user), 403);
            t.expect(t.get(base), 401);

            JsonNode connector = json(t.expect(t.json(t.csrf(t.post(base)).cookie(admin),
                    "{\"displayName\":\"Contract connector\"}"), 201));
            String id = connector.get("id").asText();
            t.expect(t.json(t.csrf(t.post(base)).cookie(admin), "{\"displayName\":\"\"}"), 400);
            t.expect(t.json(t.csrf(t.post(base)).cookie(user), "{\"displayName\":\"x\"}"), 403);
            t.expect(t.json(t.csrf(t.post(base)), "{\"displayName\":\"x\"}"), 401);

            String tokens = base + "/" + id + "/tokens";
            MvcResult issued = t.expect(t.json(t.csrf(t.post(tokens)).cookie(admin),
                    "{\"permissions\":" + TokenPermissions.READ_JSON + "}"), 201);
            assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                    .contains("no-store");
            String tokenId = json(issued).get("tokenId").asText();
            t.expect(t.json(t.csrf(t.post(tokens)).cookie(admin),
                    "{\"permissions\":" + TokenPermissions.READ_JSON + ",\"lifetimeDays\":366}"), 400);
            t.expect(t.json(t.csrf(t.post(tokens)).cookie(admin),
                    "{\"permissions\":[\"audit:read\"]}"), 400);
            t.expect(t.json(t.csrf(t.post(tokens)).cookie(user), "{\"permissions\":" + TokenPermissions.READ_JSON + "}"),
                    403);
            t.expect(t.json(t.csrf(t.post(tokens)), "{\"permissions\":" + TokenPermissions.READ_JSON + "}"), 401);
            t.expect(t.json(t.csrf(t.post(base + "/" + UUID.randomUUID() + "/tokens"))
                    .cookie(admin), "{\"permissions\":" + TokenPermissions.READ_JSON + "}"), 404);

            String rotate = tokens + "/" + tokenId + "/rotate";
            t.expect(t.json(t.csrf(t.post(rotate)).cookie(admin),
                    "{\"permissions\":[]}"), 400);
            JsonNode rotated = json(t.expect(t.json(t.csrf(t.post(rotate)).cookie(admin),
                    "{\"overlapDays\":0}"), 201));
            t.expect(t.json(t.csrf(t.post(rotate)).cookie(user), "{}"), 403);
            t.expect(t.json(t.csrf(t.post(rotate)), "{}"), 401);
            t.expect(t.json(t.csrf(t.post(tokens + "/" + UUID.randomUUID() + "/rotate"))
                    .cookie(admin), "{}"), 404);

            String revoke = tokens + "/" + rotated.get("tokenId").asText() + "/revoke";
            t.expect(t.csrf(t.post(revoke)).cookie(user), 403);
            t.expect(t.csrf(t.post(revoke)), 401);
            t.expect(t.csrf(t.post(revoke)).cookie(admin), 204);
            t.expect(t.csrf(t.post(tokens + "/" + UUID.randomUUID() + "/revoke")).cookie(admin),
                    404);

            t.expect(t.csrf(t.call(HttpMethod.DELETE, base + "/" + id)).cookie(user), 403);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, base + "/" + id)), 401);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, base + "/" + id)).cookie(admin), 204);
            t.expect(t.csrf(t.call(HttpMethod.DELETE, base + "/" + id)).cookie(admin), 404);
        });
    }

    // ---- /api/admin/audit-events ------------------------------------------------------

    private static void audit(List<Fixture> all) {
        add(all, "audit events: 200, 400, 401 and 403", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            Cookie user = t.settle(t.provision(true));
            t.expect(t.get("/api/admin/audit-events").param("size", "5").cookie(admin), 200);
            t.expect(t.get("/api/admin/audit-events").param("size", "0").cookie(admin), 400);
            t.expect(t.get("/api/admin/audit-events").param("operation", "NOPE").cookie(admin),
                    400);
            t.expect(t.get("/api/admin/audit-events").cookie(user), 403);
            t.expect(t.get("/api/admin/audit-events"), 401);
        });
    }

    // ---- /actuator --------------------------------------------------------------------

    private static void actuator(List<Fixture> all) {
        add(all, "health: 200 while the dependencies are up", t ->
                t.expect(t.get("/actuator/health"), 200));
        add(all, "prometheus: 200 for an Admin, 403 for a User, 401 for none", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            Cookie user = t.settle(t.provision(true));
            t.expect(t.get("/actuator/prometheus").cookie(admin), 200);
            t.expect(t.get("/actuator/prometheus").cookie(user), 403);
            t.expect(t.get("/actuator/prometheus"), 401);
        });
        add(all, "info: 200 for an Admin, 403 for a User, 401 for none", t -> {
            Cookie admin = t.logIn(ADMIN, ADMIN_PASSWORD);
            Cookie user = t.settle(t.provision(true));
            t.expect(t.get("/actuator/info").cookie(admin), 200);
            t.expect(t.get("/actuator/info").cookie(user), 403);
            t.expect(t.get("/actuator/info"), 401);
        });
    }

    // ---- harness ----------------------------------------------------------------------

    MockHttpServletRequestBuilder call(HttpMethod method, String path) {
        return request(method, path);
    }

    MockHttpServletRequestBuilder get(String path) {
        return call(HttpMethod.GET, path);
    }

    MockHttpServletRequestBuilder post(String path) {
        return call(HttpMethod.POST, path);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Adds the token of the request's own session, fetched as the SPA fetches it. */
    MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }

    MvcResult expect(MockHttpServletRequestBuilder request, int status) throws Exception {
        MvcResult result = RECORDER.perform(mvc, request);
        assertThat(result.getResponse().getStatus())
                .as("status of %s %s: %s", result.getRequest().getMethod(),
                        result.getRequest().getRequestURI(),
                        result.getResponse().getContentAsString())
                .isEqualTo(status);
        return result;
    }

    /** Logs in and returns the session cookie. */
    Cookie logIn(String userName, String password) throws Exception {
        MvcResult login = expect(json(csrf(post("/api/auth/login")),
                credentials(userName, password)), 200);
        Cookie session = login.getResponse().getCookie("SESSION");
        assertThat(session).as("the login issued a session cookie").isNotNull();
        return new Cookie(session.getName(), session.getValue());
    }

    /**
     * A fresh User over SCIM, with a connector-set password or none. A connector-set password
     * leaves the User confined until it changes it; see {@link #settle}.
     */
    String provision(boolean withPassword) throws Exception {
        String userName = "api-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        String body = withPassword
                ? "{\"schemas\":[\"%s\"],\"userName\":\"%s\",\"password\":\"%s\"}"
                        .formatted(USER_SCHEMA, userName, CONNECTOR_PASSWORD)
                : "{\"schemas\":[\"%s\"],\"userName\":\"%s\"}".formatted(USER_SCHEMA, userName);
        MvcResult result = expect(call(HttpMethod.POST, "/scim/v2/Users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken)
                .contentType(MediaType.valueOf("application/scim+json")).content(body), 201);
        created.add(UUID.fromString(json(result).get("id").asText()));
        return userName;
    }

    /** Replaces the connector-set password, leaving an unconfined User, and logs it in. */
    Cookie settle(String userName) throws Exception {
        Cookie confined = logIn(userName, CONNECTOR_PASSWORD);
        expect(json(csrf(post("/api/auth/change-password")).cookie(confined),
                change(CONNECTOR_PASSWORD, OWN_PASSWORD)), 204);
        return logIn(userName, OWN_PASSWORD);
    }

    UUID userId(String userName) {
        return jdbc.queryForObject(
                "SELECT resource_id FROM scim_users WHERE normalized_user_name = ?",
                UUID.class, userName);
    }

    private static String credentials(String userName, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(userName, password);
    }

    private static String change(String current, String next) {
        return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
