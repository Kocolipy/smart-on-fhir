package com.example.backend.web;

import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ServerSession;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicLoginOnContext;
import com.example.backend.observability.EcsLogCapture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
import tools.jackson.databind.JsonNode;

/**
 * Every error answer under {@code /api} carries {@link ApiError}, whichever layer refuses — the
 * security chain, the request firewall, the dispatcher or the app-wide exception handler — over
 * a real socket and the whole application chain, and none quotes the request.
 *
 * <p>A running server rather than MockMvc, because the chain's, the firewall's and the
 * dispatcher's refusals were {@code sendError}s, which a servlet container dispatches to the error
 * page and MockMvc does not: Boot's error page, which quotes the path, is visible only here.
 *
 * <p>Connector administration lives in the {@code scim} module but serves {@code /api}: its
 * refusals are the application API's, with the application API's one record, and nothing of the
 * SCIM namespace's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class ApiErrorBodyIntegrationTests extends EpicLoginOnContext {

    private static final String REQUEST_RECORD = "HTTP request completed";

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    // ---- the security chain ----------------------------------------------------------------

    @Test
    void a_signed_in_user_without_the_permission_gets_a_403_api_error() throws Exception {
        HttpResponse<String> response = send(user(), request("/api/admin/accounts").GET());

        assertApiError(response, 403, ApiError.REQUEST_REFUSED, "/api/admin/accounts");
    }

    @Test
    void no_session_gets_a_401_api_error() throws Exception {
        HttpResponse<String> response =
                send(HttpClient.newHttpClient(), request("/api/admin/accounts").GET());

        assertApiError(response, 401, ApiError.REQUEST_REFUSED, "/api/admin/accounts");
    }

    /** A path that names the API in percent-encoding is still the API's, as it is to the chain. */
    @Test
    void an_encoded_api_path_gets_the_api_error_too() throws Exception {
        HttpResponse<String> response =
                send(HttpClient.newHttpClient(), request("/%61pi/admin/accounts").GET());

        assertApiError(response, 401, ApiError.REQUEST_REFUSED, "%61pi", "admin");
    }

    @Test
    void a_signed_in_user_on_an_undeclared_route_gets_a_403_api_error() throws Exception {
        String undeclared = "/api/undeclared-" + UUID.randomUUID();

        HttpResponse<String> response = send(user(), request(undeclared).GET());

        assertApiError(response, 403, ApiError.REQUEST_REFUSED, undeclared);
    }

    // ---- the firewall and the dispatcher ---------------------------------------------------

    /** The request firewall refuses a path carrying an encoded line break before any chain runs. */
    @Test
    void the_firewalls_refusal_of_a_line_break_is_a_400_api_error() throws Exception {
        HttpResponse<String> response = send(HttpClient.newHttpClient(),
                request("/api/admin/accounts%0D%0Aforged:%20S1234567A").GET());

        assertApiError(response, 400, ApiError.INVALID_REQUEST, "forged", "S1234567A");
    }

    /**
     * Tomcat passes an encoded slash through ({@code EncodedSlashPassthrough}), and the request
     * firewall still refuses it, on every path: the refusal is the namespace's document rather
     * than Tomcat's error page, and the path is never reached.
     */
    @Test
    void the_firewalls_refusal_of_an_encoded_slash_is_a_400_api_error() throws Exception {
        HttpResponse<String> response = send(HttpClient.newHttpClient(),
                request("/api/admin%2Faccounts").GET());

        assertApiError(response, 400, ApiError.INVALID_REQUEST, "admin%2Faccounts",
                "admin/accounts");
    }

    /** A declared route sent a body type it does not read: the dispatcher's own refusal. */
    @Test
    void the_dispatchers_refusal_of_a_body_type_is_a_415_api_error() throws Exception {
        HttpClient admin = admin();

        HttpResponse<String> response = send(admin, ServerSession.withCsrf(admin, port, request("/api/admin/connectors"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("displayName=S1234567A")));

        assertApiError(response, 415, ApiError.REQUEST_REFUSED, "S1234567A", "text/plain");
    }

    // ---- connector administration ----------------------------------------------------------

    @Test
    void a_connector_with_a_blank_display_name_is_an_invalid_request_with_one_refusal()
            throws Exception {
        HttpClient admin = admin();
        logs.reset();

        HttpResponse<String> response = send(admin, createConnector(admin, "{\"displayName\":\"\"}"));

        assertApiError(response, 400, ApiError.INVALID_REQUEST, "displayName", "blank");
        assertTheOneRefusal("MethodArgumentNotValidException");
    }

    @Test
    void a_connector_with_a_malformed_body_is_an_invalid_request_with_one_refusal()
            throws Exception {
        HttpClient admin = admin();
        logs.reset();

        HttpResponse<String> response =
                send(admin, createConnector(admin, "{\"displayName\": S1234567A"));

        assertApiError(response, 400, ApiError.INVALID_REQUEST, "S1234567A", "displayName");
        assertTheOneRefusal("HttpMessageNotReadableException");
    }

    // ---- controls --------------------------------------------------------------------------

    @Test
    void a_malformed_password_change_body_is_still_an_invalid_request() throws Exception {
        HttpClient user = user();

        HttpResponse<String> response = send(user, ServerSession.withCsrf(user, port, request("/api/auth/change-password"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"currentPassword\": S1234567A")));

        assertApiError(response, 400, ApiError.INVALID_REQUEST, "S1234567A");
    }

    @Test
    void the_fhir_namespace_keeps_its_operation_outcome() throws Exception {
        HttpResponse<String> response = send(user(), request("/api/patient/%3Cimg%3E").GET());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(contentType(response)).isEqualTo(FHIR_JSON_R5);
        assertThat(JSON.readTree(response.body()))
                .isEqualTo(JSON.valueToTree(FhirOperationOutcome.of(400)));
    }

    // ---- harness ---------------------------------------------------------------------------

    /**
     * The status, {@code application/json}, exactly the {@link ApiError} for it — and no part of
     * the request in the body, which Boot's error page would quote.
     */
    private void assertApiError(HttpResponse<String> response, int status, String code,
            String... fromTheRequest) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(contentType(response)).isEqualTo("application/json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body).isEqualTo(JSON.valueToTree(
                ApiError.of(org.springframework.http.HttpStatusCode.valueOf(status))));
        assertThat(response.body()).doesNotContain("timestamp", "path", "/api").doesNotContain(fromTheRequest);
    }

    /**
     * The request's only record at {@code WARN} or above, besides the request record, is the
     * application API's one refusal record — never a SCIM refusal.
     */
    private void assertTheOneRefusal(String reason) throws Exception {
        logs.awaitRequestRecords(1);
        List<JsonNode> warnOrAbove = logs.records().stream()
                .filter(record -> !REQUEST_RECORD.equals(record.at("/message").asText()))
                .filter(record -> List.of("WARN", "ERROR").contains(record.at("/log/level").asText()))
                .toList();
        assertThat(warnOrAbove).as("records for the refusal, besides the request record")
                .singleElement()
                .satisfies(refusal -> {
                    assertThat(refusal.at("/log/level").asText()).isEqualTo("WARN");
                    assertThat(refusal.at("/message").asText()).isEqualTo("Request refused");
                    assertThat(refusal.at("/event/reason").asText()).isEqualTo(reason);
                    assertThat(refusal.at("/http/response/status_code").asInt()).isEqualTo(400);
                });
        assertThat(logs.lines()).doesNotContain("SCIM request refused", "scim.refusal",
                "S1234567A");
    }

    private HttpRequest.Builder createConnector(HttpClient admin, String body) throws Exception {
        return ServerSession.withCsrf(admin, port, request("/api/admin/connectors"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("").replace(" ", "");
    }

    private HttpClient user() throws Exception {
        return ServerSession.logIn(port, "test-user", "test-password");
    }

    private HttpClient admin() throws Exception {
        return ServerSession.logIn(port, "test-admin", "test-admin-password");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
