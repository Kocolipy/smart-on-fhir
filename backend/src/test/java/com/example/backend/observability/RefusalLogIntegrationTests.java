package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicLoginOnContext;
import com.example.backend.TokenPermissions;
import com.example.backend.scim.application.ConnectorAdministrationService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each refusal is ONE record, observed over a real socket.
 *
 * <p>A running server rather than MockMvc because a double record could only come from what a
 * servlet container adds: a {@code 401} or {@code 403} answered with {@code sendError} is
 * dispatched again to the error page, and MockMvc performs no error dispatch, so it would pass
 * whatever that second pass wrote.
 *
 * <p>For each refused request the whole stream is read, and every record at {@code WARN} or
 * above except the request record ({@code #68}, which exists beside the refusal and is not
 * counted) must be the one refusal record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class RefusalLogIntegrationTests extends EpicLoginOnContext {

    private static final String REQUEST_RECORD = "HTTP request completed";

    private static final String SCIM_JSON = "application/scim+json";

    private final JsonMapper json = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    private EcsLogCapture logs;

    private String writeToken;

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create("refusal-log-connector", null).id();
        writeToken = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, null, TokenPermissions.ALL).presentedValue();
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
        created.forEach(id -> jdbc.update(
                "DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL",
                UUID.fromString(id)));
        created.clear();
    }

    @Test
    void an_anonymous_self_read_is_one_refusal_record() throws Exception {
        HttpClient anonymous = HttpClient.newHttpClient();

        JsonNode refusal = theOneRefusal(anonymous, request("/api/self").GET(), 401);

        assertThat(refusal.at("/event/reason").asText()).isEqualTo("no-session");
        assertThat(refusal.at("/http/route").asText()).isEqualTo("/api/self");
    }

    @Test
    void a_non_admin_admin_read_is_one_refusal_record() throws Exception {
        HttpClient user = login("test-user", "test-password");

        JsonNode refusal = theOneRefusal(user, request("/api/admin/accounts").GET(), 403);

        assertThat(refusal.at("/event/reason").asText()).isEqualTo("insufficient-permissions");
        assertThat(refusal.at("/user/id").asText()).isNotBlank();
    }

    /**
     * A session confined by a required password change: the forced-change 403, whose error
     * page the session holds no role for either.
     */
    @Test
    void a_confined_sessions_refusal_is_one_record() throws Exception {
        String userName = "refusal-confined-" + UUID.randomUUID();
        HttpResponse<String> provisioned = send(HttpClient.newHttpClient(),
                scim("/Users").POST(HttpRequest.BodyPublishers.ofString("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","password":"connector-chosen-1"}"""
                        .formatted(userName))));
        assertThat(provisioned.statusCode()).isEqualTo(201);
        created.add(json.readTree(provisioned.body()).get("id").asText());
        HttpClient confined = login(userName, "connector-chosen-1");

        JsonNode refusal = theOneRefusal(confined, request("/api/self").GET(), 403);

        assertThat(refusal.at("/event/reason").asText()).isEqualTo("insufficient-permissions");
        assertThat(refusal.at("/user/id").asText()).isEqualTo(created.getFirst());
    }

    @Test
    void a_missing_csrf_token_is_one_refusal_record_and_not_an_authorization_one()
            throws Exception {
        HttpClient user = login("test-user", "test-password");

        JsonNode refusal = theOneRefusal(user, request("/api/auth/change-password")
                .POST(HttpRequest.BodyPublishers.noBody()), 403);

        assertThat(refusal.at("/event/reason").asText()).isEqualTo("csrf");
    }

    @Test
    void a_bad_bearer_is_one_refusal_record_carrying_nothing_of_the_value() throws Exception {
        String presented = "refusal-bearer-probe-" + UUID.randomUUID();

        JsonNode refusal = theOneRefusal(HttpClient.newHttpClient(), request("/scim/v2/Users")
                .header("Authorization", "Bearer " + presented).GET(), 401);

        assertThat(refusal.at("/event/reason").asText()).isEqualTo("bearer-invalid");
        assertThat(logs.lines()).doesNotContain(presented).doesNotContain("refusal-bearer-probe");
    }

    @Test
    void a_scim_refusal_is_one_record_beside_the_request_record() throws Exception {
        JsonNode refusal = theOneRefusal(HttpClient.newHttpClient(),
                scim("/Users/" + UUID.randomUUID()).GET(), 404);

        assertThat(refusal.at("/message").asText()).isEqualTo("SCIM request refused");
        assertThat(refusal.at("/event/reason").asText()).isEqualTo("notFound");
        assertThat(refusal.at("/scim/connector/id").asText()).isNotBlank();
        assertThat(logs.lines()).doesNotContain(writeToken);
    }

    /**
     * The refusals the SCIM chain answers before or instead of a handler — a declared body over
     * the bound, a method or a body type the endpoint does not take — are one record each, of
     * the same shape as a handler's refusal.
     */
    @Test
    void an_oversized_scim_body_is_one_refusal_record() throws Exception {
        byte[] oversized = new byte[(int) com.example.backend.scim.domain.ScimRequestLimits
                .MAX_BODY_BYTES + 1];

        JsonNode refusal = theOneRefusal(HttpClient.newHttpClient(), scim("/Users")
                .POST(HttpRequest.BodyPublishers.ofByteArray(oversized)), 413);

        assertThat(refusal.at("/message").asText()).isEqualTo("SCIM request refused");
        assertThat(refusal.at("/event/reason").asText()).isEqualTo("payloadTooLarge");
        assertThat(refusal.at("/log/level").asText()).isEqualTo("WARN");
    }

    @Test
    void an_unsupported_scim_method_is_one_refusal_record() throws Exception {
        JsonNode refusal = theOneRefusal(HttpClient.newHttpClient(),
                scim("/Users").DELETE(), 405);

        assertThat(refusal.at("/message").asText()).isEqualTo("SCIM request refused");
        assertThat(refusal.at("/event/reason").asText()).isEqualTo("methodNotAllowed");
        assertThat(refusal.at("/scim/connector/id").asText()).isNotBlank();
        assertThat(logs.lines()).as("the dispatcher's own message, which quotes the request")
                .doesNotContain("Request method");
    }

    @Test
    void an_unsupported_scim_body_type_is_one_refusal_record() throws Exception {
        JsonNode refusal = theOneRefusal(HttpClient.newHttpClient(), scim("/Users")
                .setHeader("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("userName=refused")), 415);

        assertThat(refusal.at("/message").asText()).isEqualTo("SCIM request refused");
        assertThat(refusal.at("/event/reason").asText()).isEqualTo("unsupportedMediaType");
        assertThat(logs.lines()).doesNotContain("userName=refused").doesNotContain("text/plain");
    }

    // ---- harness -----------------------------------------------------------------------------

    /**
     * Sends one refused request on a fresh capture, waits for its request record, and returns
     * the single non-request record at {@code WARN} or above — failing if there is none, or
     * more than one.
     */
    private JsonNode theOneRefusal(HttpClient client, HttpRequest.Builder request, int status)
            throws Exception {
        logs.reset();
        assertThat(send(client, request).statusCode()).isEqualTo(status);
        logs.awaitRequestRecords(1);

        List<JsonNode> warnOrAbove = logs.records().stream()
                .filter(record -> !REQUEST_RECORD.equals(record.at("/message").asText()))
                .filter(record -> List.of("WARN", "ERROR").contains(record.at("/log/level").asText()))
                .toList();
        assertThat(warnOrAbove).as("records for the refusal, besides the request record")
                .hasSize(1);
        assertThat(logs.records()).filteredOn(record ->
                        REQUEST_RECORD.equals(record.at("/message").asText()))
                .as("the request record, once").hasSize(1);
        JsonNode refusal = warnOrAbove.getFirst();
        assertThat(refusal.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(refusal.at("/http/request/id").asText()).isNotBlank();
        return refusal;
    }

    private HttpClient login(String username, String password) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf = json.readTree(send(client, request("/api/auth/csrf").GET()).body());
        HttpResponse<String> response = send(client, request("/api/auth/login")
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", username, "password", password)))));
        assertThat(response.statusCode()).as("login of %s", username).isEqualTo(200);
        return client;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpRequest.Builder scim(String path) {
        return request("/scim/v2" + path)
                .header("Authorization", "Bearer " + writeToken)
                .header("Content-Type", SCIM_JSON)
                .header("Accept", SCIM_JSON);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
