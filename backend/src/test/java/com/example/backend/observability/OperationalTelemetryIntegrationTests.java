package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.epic.EpicTestKeys;
import com.example.backend.scim.application.ConnectorAdministrationService;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operational-telemetry demo oracle: sample traffic over a real socket, then a real
 * scrape of {@code /actuator/prometheus}, read back as the text a Prometheus server
 * would ingest.
 *
 * <p>A running server and {@link HttpClient} rather than MockMvc on purpose. What is
 * claimed is about the deployed request path end to end — the observation filter around
 * both security chains, the Tomcat thread pool the saturation gauges read, a real Redis
 * session behind the Admin cookie — and MockMvc has neither a socket nor Tomcat.
 *
 * <p>The traffic is generated once, in {@link #sampleTraffic()}, and every test reads the
 * one scrape taken after it: each is a different claim about the same observation. The
 * values the traffic carried — a userName, an externalId, filter text, a resource id, a
 * token — are collected in {@link #forbidden} as they are sent, so the absence check is
 * over what really crossed the wire rather than a list written separately.
 *
 * <p>Epic Login is on, so the {@code epic.outbound} series it publishes from startup are in
 * the scrape its alert selects on. Nothing here launches from Epic, and startup contacts
 * no Epic, so its issuer need not answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalTelemetryIntegrationTests {

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EpicTestKeys.epicLoginOn(registry, EpicTestKeys::p384Pem, EpicTestKeys::p384Pem);
    }

    private static final String ADMIN = "test-admin";

    private static final String ADMIN_PASSWORD = "test-admin-password";

    private static final String USER = "test-user";

    private static final String USER_PASSWORD = "test-password";

    private static final String SCIM_JSON = "application/scim+json";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String REQUESTS = "http_server_requests_seconds_count";

    private static final Path ALERT_RULES = Path.of("ops/prometheus/alerts.yaml");

    /** One Prometheus text-format sample: {@code name{labels} value}. */
    private static final Pattern SAMPLE =
            Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})?\\s+\\S+.*$");

    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** A PromQL vector selector with a label-matcher block. */
    private static final Pattern SELECTOR =
            Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\{([^}]*)}");

    private static final Pattern MATCHER =
            Pattern.compile("(\\w+)\\s*(=~|!~|!=|=)\\s*\"([^\"]*)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private ConnectorAdministrationService connectors;

    private final JsonMapper json = JsonMapper.builder().build();

    /** Every identifier, filter and secret the sample traffic carried. */
    private final List<String> forbidden = new ArrayList<>();

    private UUID connectorId;

    private String scimUserId;

    private String adminScrape;

    private HttpResponse<String> connectorScrape;

    private HttpResponse<String> userScrape;

    private HttpResponse<String> anonymousScrape;

    @BeforeAll
    void sampleTraffic() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userName = "telemetry-probe-" + suffix;
        String externalId = "telemetry-ext-" + suffix;
        String filter = "userName eq \"" + userName + "\"";

        connectorId = connectors.create("Telemetry probe " + suffix, ADMIN).id();
        String token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, ADMIN, TokenPermissions.ALL).presentedValue();
        forbidden.addAll(List.of(userName, externalId, filter, token));

        HttpClient connector = HttpClient.newHttpClient();
        String create = """
                {"schemas":["%s"],"userName":"%s","externalId":"%s","active":true}"""
                .formatted(USER_SCHEMA, userName, externalId);

        // 201, then 409 uniqueness for the same userName.
        HttpResponse<String> created = send(connector, scim("/Users", token)
                .POST(HttpRequest.BodyPublishers.ofString(create)));
        assertThat(created.statusCode()).isEqualTo(201);
        scimUserId = json.readTree(created.body()).get("id").asString();
        forbidden.add(scimUserId);
        assertThat(send(connector, scim("/Users", token)
                .POST(HttpRequest.BodyPublishers.ofString(create))).statusCode())
                .isEqualTo(409);

        // A read by id, a filtered list, and a filter refused as unparseable.
        assertThat(send(connector, scim("/Users/" + scimUserId, token).GET()).statusCode())
                .isEqualTo(200);
        assertThat(send(connector, scim("/Users?filter=" + encode(filter), token).GET())
                .statusCode()).isEqualTo(200);
        String badFilter = "userName eq " + externalId;
        forbidden.add(badFilter);
        assertThat(send(connector, scim("/Users?filter=" + encode(badFilter), token).GET())
                .statusCode()).isEqualTo(400);

        // An unconditional write (no If-Match) is applied; a stale If-Match is a 412.
        String replace = """
                {"schemas":["%s"],"userName":"%s","active":true}"""
                .formatted(USER_SCHEMA, userName);
        assertThat(send(connector, scim("/Users/" + scimUserId, token)
                .PUT(HttpRequest.BodyPublishers.ofString(replace))).statusCode())
                .isEqualTo(200);
        assertThat(send(connector, scim("/Users/" + scimUserId, token)
                .header("If-Match", "\"999999\"")
                .PUT(HttpRequest.BodyPublishers.ofString(replace))).statusCode())
                .isEqualTo(412);

        // 401: no credential, then a token that is not one.
        String notAToken = "telemetry-not-a-token-" + suffix;
        forbidden.add(notAToken);
        assertThat(send(connector, request("/scim/v2/Users").GET()).statusCode())
                .isEqualTo(401);
        assertThat(send(connector, scim("/Users", notAToken).GET()).statusCode())
                .isEqualTo(401);

        // Login: one refused (an account that does not exist, so no real account's
        // failure run is touched), then the two sessions used below.
        String strangerName = "telemetry-stranger-" + suffix;
        String strangerPassword = "telemetry-wrong-password-" + suffix;
        forbidden.addAll(List.of(strangerName, strangerPassword, ADMIN_PASSWORD, USER_PASSWORD));
        assertThat(login(strangerName, strangerPassword).status()).isEqualTo(401);
        Session admin = login(ADMIN, ADMIN_PASSWORD);
        assertThat(admin.status()).isEqualTo(200);
        Session user = login(USER, USER_PASSWORD);
        assertThat(user.status()).isEqualTo(200);
        assertThat(admin.cookies()).hasSizeGreaterThanOrEqualTo(2);
        forbidden.addAll(admin.cookies());
        forbidden.addAll(user.cookies());

        // The metrics endpoint, tried by everyone who is not entitled to it first.
        connectorScrape = send(connector, request("/actuator/prometheus")
                .header("Authorization", "Bearer " + token).GET());
        anonymousScrape = send(connector, request("/actuator/prometheus").GET());
        userScrape = send(user.client(), request("/actuator/prometheus").GET());
        HttpResponse<String> scrape =
                send(admin.client(), request("/actuator/prometheus").GET());
        assertThat(scrape.statusCode()).isEqualTo(200);
        adminScrape = scrape.body();
        // Kept beside the build output, so the scrape these tests judged can be read by a
        // person rather than only described by an assertion message.
        Files.writeString(Path.of("target/telemetry-sample-scrape.txt"), adminScrape);
    }

    @AfterAll
    void removeWhatThisClassCreated() throws Exception {
        if (connectorId == null) {
            return;
        }
        if (scimUserId != null) {
            String token = connectors.issueToken(
                    connectorId, TokenPermissions.ALL, null, ADMIN, TokenPermissions.ALL).presentedValue();
            HttpResponse<String> current = send(
                    HttpClient.newHttpClient(), scim("/Users/" + scimUserId, token).GET());
            String etag = current.headers().firstValue("ETag").orElseThrow();
            send(HttpClient.newHttpClient(), scim("/Users/" + scimUserId, token)
                    .header("If-Match", etag).DELETE());
        }
        connectors.delete(connectorId, ADMIN);
    }

    // ---- AC: metrics exist with the required tag dimensions -----------------------

    /**
     * A SCIM endpoint's requests carry endpoint (the route template), method, resource
     * type and status class, plus the connector that made them.
     */
    @Test
    void a_scim_endpoint_is_counted_by_endpoint_method_resource_type_status_class_and_connector() {
        assertThat(series(REQUESTS, Map.of(
                "uri", "/scim/v2/Users",
                "method", "POST",
                "status", "201",
                "outcome", "SUCCESS",
                "scim_resource_type", "User",
                "scim_type", "none",
                "scim_connector", connectorId.toString())))
                .as("the create, by its route and connector").hasSize(1);
        assertThat(series(REQUESTS, Map.of(
                "uri", "/scim/v2/Users/{id}",
                "method", "GET",
                "status", "200",
                "scim_resource_type", "User")))
                .as("a read by id is tagged with the route TEMPLATE, never the id")
                .hasSize(1);
    }

    /** "Errors: rate by status class and, for 4xx, by scimType". */
    @Test
    void a_scim_4xx_is_counted_by_its_scim_type() {
        assertThat(series(REQUESTS, Map.of(
                "uri", "/scim/v2/Users", "method", "POST", "status", "409",
                "outcome", "CLIENT_ERROR", "scim_type", "uniqueness")))
                .hasSize(1);
        assertThat(series(REQUESTS, Map.of(
                "uri", "/scim/v2/Users", "method", "GET", "status", "400",
                "outcome", "CLIENT_ERROR", "scim_type", "invalidFilter")))
                .hasSize(1);
    }

    /**
     * A request the SCIM chain refused before any route matched still says it was a SCIM
     * request, which is what lets the authentication-failure alert tell it apart from a
     * {@code 401} anywhere else.
     */
    @Test
    void a_refused_scim_credential_is_still_attributed_to_the_scim_resource_type() {
        List<Map<String, String>> refused = series(REQUESTS, Map.of(
                "status", "401", "scim_resource_type", "User", "scim_connector", "none"));
        assertThat(refused).isNotEmpty();
        assertThat(refused).allSatisfy(labels -> assertThat(labels.get("uri"))
                .as("no route matched, so Spring cannot name one")
                .isEqualTo("UNKNOWN"));
    }

    /** Latency is a histogram — percentiles, not an average — for SCIM and for Login. */
    @Test
    void scim_and_login_latency_are_published_as_histograms() {
        assertThat(series("http_server_requests_seconds_bucket", Map.of(
                "uri", "/api/auth/login", "method", "POST", "status", "200")))
                .as("Login latency buckets").hasSizeGreaterThan(1);
        assertThat(series("http_server_requests_seconds_bucket", Map.of(
                "uri", "/api/auth/login", "method", "POST", "status", "401",
                "outcome", "CLIENT_ERROR", "scim_resource_type", "none")))
                .as("refused Login latency buckets").hasSizeGreaterThan(1);
        assertThat(series("http_server_requests_seconds_bucket", Map.of(
                "uri", "/scim/v2/Users", "method", "POST", "status", "201")))
                .as("SCIM latency buckets").hasSizeGreaterThan(1);
    }

    /** Saturation: the database pool, Tomcat's request threads, Redis, the scheduled jobs. */
    @Test
    void saturation_signals_are_published() {
        assertThat(series("hikaricp_connections_active", Map.of())).isNotEmpty();
        assertThat(series("hikaricp_connections_max", Map.of())).isNotEmpty();
        assertThat(series("hikaricp_connections_pending", Map.of())).isNotEmpty();
        assertThat(series("tomcat_threads_busy_threads", Map.of())).isNotEmpty();
        assertThat(series("tomcat_threads_config_max_threads", Map.of())).isNotEmpty();
        assertThat(series("lettuce_active_seconds_count", Map.of("db_system", "redis")))
                .as("in-flight Redis commands, by command name only").isNotEmpty();
        assertThat(series("lettuce_seconds_count", Map.of("db_system", "redis")))
                .as("Redis command latency").isNotEmpty();
        assertThat(series("app_job_runs_total",
                Map.of("job", "audit-retention", "outcome", "failure")))
                .as("a job's failure series exists before it has ever failed").hasSize(1);
        assertThat(series("app_job_last_success_seconds", Map.of("job", "audit-retention")))
                .hasSize(1);
    }

    /**
     * No identifier, filter text or secret the traffic carried appears anywhere in the
     * scrape — not only in no tag, but nowhere at all.
     *
     * <p>Non-vacuous twice over: {@link #forbidden} is filled from the values actually
     * sent, and the scrape is first shown to contain the series that traffic produced, so
     * an empty or truncated body cannot pass it.
     */
    @Test
    void no_identifier_filter_or_secret_appears_in_the_scrape() {
        assertThat(series(REQUESTS, Map.of("scim_connector", connectorId.toString())))
                .as("the scrape describes the traffic whose values it must not contain")
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(forbidden).hasSizeGreaterThanOrEqualTo(10).doesNotContainNull();
        for (String value : forbidden) {
            assertThat(adminScrape).as("scrape contains %s", value).doesNotContain(value);
            assertThat(adminScrape).doesNotContain(encode(value));
        }
    }

    /**
     * A write applied without {@code If-Match} is counted as unconditional, per connector, so an
     * operator can see which integrations write without lost-update protection; a write that
     * sent one is counted as such, and a request that is not such a write carries neither.
     */
    @Test
    void writes_are_counted_by_whether_they_carried_if_match() {
        assertThat(series(REQUESTS, Map.of(
                "uri", "/scim/v2/Users/{id}", "method", "PUT", "status", "200",
                "outcome", "SUCCESS", "scim_connector", connectorId.toString(),
                "scim_precondition", "unconditional")))
                .as("the unconditional replacement").hasSize(1);
        assertThat(series(REQUESTS, Map.of(
                "uri", "/scim/v2/Users/{id}", "method", "PUT", "status", "412",
                "scim_connector", connectorId.toString(), "scim_precondition", "if-match")))
                .as("the stale conditional replacement").hasSize(1);
        assertThat(series(REQUESTS, Map.of("uri", "/scim/v2/Users", "method", "POST")))
                .as("a create is not a write against an existing resource")
                .isNotEmpty()
                .allSatisfy(labels -> assertThat(labels.get("scim_precondition")).isEqualTo("none"));
    }

    // ---- AC: access gating --------------------------------------------------------

    @Test
    void a_connector_token_cannot_reach_the_metrics_endpoint() {
        assertThat(connectorScrape.statusCode()).isEqualTo(401);
        assertThat(connectorScrape.body()).doesNotContain(REQUESTS);
    }

    @Test
    void neither_an_anonymous_caller_nor_an_ordinary_user_can_reach_it() {
        assertThat(anonymousScrape.statusCode()).isEqualTo(401);
        assertThat(userScrape.statusCode()).isEqualTo(403);
        assertThat(userScrape.body()).doesNotContain(REQUESTS);
    }

    @Test
    void an_admin_session_can_reach_the_metrics_endpoint() {
        assertThat(adminScrape).contains("# TYPE " + REQUESTS.replace("_count", "") + " histogram");
    }

    // ---- AC: the four alert conditions are code, and they select real series ------

    @Test
    void the_alert_rules_cover_the_four_conditions() throws IOException {
        Map<String, String> rules = alertRules();
        assertThat(rules).containsOnlyKeys(
                "ScimAuthenticationFailuresSustained",
                "LoginAuthenticationFailuresSustained",
                "ScimPreconditionFailuresSustained",
                "ScimUniquenessConflictsSustained",
                "DormancyJobFailed",
                "DormancyJobNotRunning",
                "EpicJwksFetchFailing",
                "EpicEndpointUnavailable",
                "EpicClientCredentialRefused",
                "EpicLoginRefusalsSurge",
                "scim:unconditional_writes:rate1h");
        assertThat(rules.get("ScimAuthenticationFailuresSustained")).contains("status=\"401\"");
        assertThat(rules.get("LoginAuthenticationFailuresSustained")).contains("status=\"401\"");
        assertThat(rules.get("ScimPreconditionFailuresSustained"))
                .contains("status=\"412\"").doesNotContain("428");
        assertThat(rules.get("scim:unconditional_writes:rate1h"))
                .contains("scim_precondition=\"unconditional\"").contains("scim_connector");
        assertThat(rules.get("ScimUniquenessConflictsSustained")).contains("status=\"409\"");
        assertThat(rules.get("DormancyJobFailed")).contains("job=\"dormancy\"");
        assertThat(rules.get("DormancyJobNotRunning")).contains("job=\"dormancy\"");
        assertThat(rules.get("EpicJwksFetchFailing"))
                .contains("epic_outbound_errors_total{call=\"jwks\"}");
        assertThat(rules.get("EpicEndpointUnavailable"))
                .contains("epic_outbound_errors_total{call=~\"token|discovery\"}");
        assertThat(rules.get("EpicClientCredentialRefused"))
                .contains("epic_login_failed_calls_total{call=\"token\", error_category=\"cert/auth\"}");
        assertThat(rules.get("EpicLoginRefusalsSurge"))
                .contains("epic_login_total{outcome=\"refused\"}");
    }

    /**
     * Every selector in every rule matches a series the sample traffic produced, so a
     * renamed metric or tag breaks the build rather than silently disarming an alert. The
     * dormancy rules are matched against the dormancy job's own series, which exist
     * from the moment it is scheduled ({@link ScheduledJobMetrics}).
     */
    @Test
    void every_alert_selector_matches_a_series_in_the_scrape() throws IOException {
        Map<String, String> rules = alertRules();
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            Matcher selectors = SELECTOR.matcher(rule.getValue());
            int found = 0;
            while (selectors.find()) {
                found++;
                String metric = selectors.group(1);
                String matchers = selectors.group(2);
                assertThat(matching(metric, matchers))
                        .as("%s: %s{%s}", rule.getKey(), metric, matchers)
                        .isNotEmpty();
            }
            assertThat(found).as("%s has a selector", rule.getKey()).isPositive();
        }
    }

    // ---- scrape and rule reading --------------------------------------------------

    /** The label sets of {@code metric}'s samples that carry every one of {@code required}. */
    private List<Map<String, String>> series(String metric, Map<String, String> required) {
        return samples(metric).stream()
                .filter(labels -> required.entrySet().stream()
                        .allMatch(e -> e.getValue().equals(labels.get(e.getKey()))))
                .toList();
    }

    private List<Map<String, String>> matching(String metric, String matchers) {
        Matcher m = MATCHER.matcher(matchers);
        List<String[]> parsed = new ArrayList<>();
        while (m.find()) {
            parsed.add(new String[] {m.group(1), m.group(2), m.group(3)});
        }
        return samples(metric).stream().filter(labels -> parsed.stream().allMatch(p -> {
            String actual = labels.getOrDefault(p[0], "");
            return switch (p[1]) {
                case "=" -> actual.equals(p[2]);
                case "!=" -> !actual.equals(p[2]);
                case "=~" -> actual.matches(p[2]);
                default -> !actual.matches(p[2]);
            };
        })).toList();
    }

    private List<Map<String, String>> samples(String metric) {
        List<Map<String, String>> found = new ArrayList<>();
        for (String line : adminScrape.split("\n")) {
            Matcher sample = SAMPLE.matcher(line);
            if (line.startsWith("#") || !sample.matches() || !sample.group(1).equals(metric)) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            if (sample.group(2) != null) {
                Matcher label = LABEL.matcher(sample.group(2));
                while (label.find()) {
                    labels.put(label.group(1), label.group(2));
                }
            }
            found.add(labels);
        }
        return found;
    }

    /**
     * Rule name to expression, from the deployed rule file itself: an alerting rule by its
     * {@code alert}, a recording rule by its {@code record}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> alertRules() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(ALERT_RULES));
        Map<String, String> rules = new LinkedHashMap<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                String name = (String) (rule.containsKey("alert") ? rule.get("alert") : rule.get("record"));
                rules.put(name, (String) rule.get("expr"));
            }
        }
        return rules;
    }

    // ---- HTTP ---------------------------------------------------------------------

    private record Session(int status, HttpClient client, List<String> cookies) {
    }

    /**
     * A password login through the real CSRF handshake: {@code GET /api/auth/csrf} opens a
     * session and returns its token, and the login echoes it in the header that response
     * names, exactly as the SPA does.
     */
    private Session login(String username, String password) throws Exception {
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(jar).build();
        JsonNode csrf = json.readTree(send(client, request("/api/auth/csrf").GET()).body());
        String token = csrf.get("token").asText();
        String body = json.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = send(client, request("/api/auth/login")
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), token)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        // Every cookie the login left behind (the session's, whatever this context names
        // it) and the CSRF token: all of them are secrets a scrape must not echo.
        List<String> secrets = new ArrayList<>();
        if (response.statusCode() == 200) {
            jar.getCookieStore().getCookies().forEach(cookie -> secrets.add(cookie.getValue()));
            secrets.add(token);
        }
        return new Session(response.statusCode(), client, List.copyOf(secrets));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpRequest.Builder scim(String path, String token) {
        return request("/scim/v2" + path)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", SCIM_JSON)
                .header("Accept", SCIM_JSON);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
