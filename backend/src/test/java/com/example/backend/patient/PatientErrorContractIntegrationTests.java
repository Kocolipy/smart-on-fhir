package com.example.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.epic.EpicLoginOnContext;
import com.example.backend.observability.EcsLogCapture;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * {@code /api/patient/{id}}'s error contract and its logging, over a real socket and the whole
 * application chain: every refusal is an {@code OperationOutcome} in FHIR JSON whichever layer
 * makes it — the app-wide handler, the chain, or the dispatcher — and no read, refused or not,
 * puts a patient's data or the id it was asked for in the log stream.
 *
 * <p>A running server rather than MockMvc, because the chain's and the dispatcher's refusals are
 * {@code sendError}s, which a servlet container dispatches again to the error page and MockMvc does
 * not: a body only the container's error dispatch would write is invisible there.
 *
 * <p>The patient is this class's own row, with values no other record could hold by chance, so a
 * value found in the stream can only have come from it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class PatientErrorContractIntegrationTests extends EpicLoginOnContext {

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    private static final String NRIC = "T7391046Q";

    private static final String NAME_TEXT = "Quah Seow Peng";

    private static final String BIRTH_DATE = "1963-07-29";

    /** One character past the FHIR id grammar's 64, and nothing else wrong with it. */
    private static final String TOO_LONG =
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    private static final String REQUEST_RECORD = "HTTP request completed";

    private final JsonMapper json = JsonMapper.builder().build();

    /** This class's own row's FHIR id. */
    private final String id = "error-contract-" + UUID.randomUUID();

    private final List<String> provisioned = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    @Autowired
    private ConnectorAdministrationService connectors;

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO patients (id, nric, name_text, name_family, name_given, gender,"
                + " birth_date) VALUES (?, ?, ?, 'Quah', 'Seow Peng', 'female', ?)",
                id, NRIC, NAME_TEXT, BIRTH_DATE);
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
        // The app login holds no DELETE on patients; the owner removes the row.
        logins.owner().jdbc().update("DELETE FROM patients WHERE id = ?", id);
        provisioned.forEach(user -> jdbc.update(
                "DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL",
                UUID.fromString(user)));
    }

    // ---- the error contract --------------------------------------------------------------

    /**
     * One character past FHIR's 64, markup, an encoded line break and an empty segment in place of
     * the id. The line break and the empty segment never reach the dispatcher — the request
     * firewall refuses them — and are answered with the same outcome.
     */
    @ParameterizedTest
    @ValueSource(strings = {TOO_LONG, "%3Cimg%20src=x%20onerror=alert(1)%3E",
        "1%0D%0Aforged:%20S1234567A", "a//b"})
    void aMalformedIdIsAnInvalidOutcome(String id) throws Exception {
        HttpResponse<String> response = send(signedIn(), patient(id).GET());

        assertThat(answer(response)).isEqualTo(answer(400, "invalid", "The request is not valid."));
    }

    /** A well-formed id no patient is held under: {@code not-a-uuid} is a FHIR id like any other. */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "eq081-VQEgP8drUUqCWzHfw3-absent"})
    void anUnknownIdIsANotFoundOutcome(String unknown) throws Exception {
        HttpResponse<String> response = send(signedIn(), patient(unknown).GET());

        assertThat(answer(response))
                .isEqualTo(answer(404, "not-found", "The resource was not found."));
    }

    @Test
    void aSessionWithoutPatientReadIsAForbiddenOutcome() throws Exception {
        HttpResponse<String> response = send(confined(), patient(id).GET());

        assertThat(answer(response))
                .isEqualTo(answer(403, "forbidden", "The request is not permitted."));
    }

    /**
     * A write is refused as one the resource does not support, not as a refusal of the caller:
     * sent with the session's CSRF token, it passes the chain and the dispatcher answers it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aMethodOtherThanAReadIsANotSupportedOutcome(String method) throws Exception {
        HttpClient session = signedIn();

        HttpResponse<String> response = send(session, withCsrf(session, patient(id))
                .method(method, HttpRequest.BodyPublishers.noBody()));

        assertThat(answer(response))
                .isEqualTo(answer(405, "not-supported", "The request is not supported."));
    }

    /** The 403's one record is the existing authorization refusal's, classified as cert/auth. */
    @Test
    void aSessionWithoutPatientReadIsRecordedAsAnAuthorizationRefusal() throws Exception {
        HttpClient confined = confined();
        logs.reset();

        send(confined, patient(id).GET());
        logs.awaitRequestRecords(1);

        assertThat(logs.records()).filteredOn(record ->
                        "insufficient-permissions".equals(record.at("/event/reason").asText()))
                .singleElement()
                .satisfies(refusal -> assertThat(List.of(
                        refusal.at("/log/level").asText(),
                        refusal.at("/error/category").asText()))
                        .containsExactly("WARN", "cert/auth"));
    }

    // ---- logging -------------------------------------------------------------------------

    /**
     * A malformed id and an unknown one add no record to the request's own: that record is the
     * request's only one, and it carries the refusal's classification, the caller's error.
     */
    @ParameterizedTest
    @ValueSource(strings = {TOO_LONG, "unknown"})
    void aRefusedReadsOnlyRecordIsItsRequestRecordClassifiedAsData(String asked)
            throws Exception {
        HttpClient session = signedIn();
        String path = asked.equals("unknown") ? "unknown-" + UUID.randomUUID() : asked;
        logs.reset();

        send(session, patient(path).GET());
        logs.awaitRequestRecords(1);

        JsonNode request = logs.records().stream()
                .filter(record -> REQUEST_RECORD.equals(record.at("/message").asText()))
                .findFirst().orElseThrow();
        String requestId = request.at("/http/request/id").asText();
        assertThat(logs.records()).filteredOn(record ->
                        requestId.equals(record.at("/http/request/id").asText()))
                .singleElement()
                .satisfies(only -> assertThat(List.of(
                        only.at("/log/level").asText(),
                        only.at("/error/category").asText()))
                        .containsExactly("WARN", "data"));
    }

    /**
     * A read that succeeds, one refused for a malformed id and one for an unknown id: the stream
     * names none of the patient's NRIC, name or date of birth, and none of the ids asked for —
     * the request record names the route template, never the path.
     */
    @Test
    void noReadPutsThePatientOrTheIdAskedForInTheLog() throws Exception {
        HttpClient session = signedIn();
        String unknown = UUID.randomUUID().toString();
        String malformed = "probe-" + UUID.randomUUID() + "-" + "x".repeat(64);
        logs.reset();

        assertThat(send(session, patient(id).GET()).statusCode()).isEqualTo(200);
        assertThat(send(session, patient(malformed).GET()).statusCode()).isEqualTo(400);
        assertThat(send(session, patient(unknown).GET()).statusCode()).isEqualTo(404);
        logs.awaitRequestRecords(3);

        assertThat(logs.lines())
                .contains(REQUEST_RECORD)
                .doesNotContain(NRIC, NAME_TEXT, "Seow Peng", BIRTH_DATE,
                        id, unknown, malformed);
    }

    // ---- harness -------------------------------------------------------------------------

    /** What a client reads off a response: its status, its media type and its body. */
    private record Answer(int status, String contentType, JsonNode body) {
    }

    private Answer answer(HttpResponse<String> response) {
        return new Answer(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null).replace(" ", ""),
                json.readTree(response.body()));
    }

    private Answer answer(int status, String code, String diagnostics) {
        return new Answer(status, FHIR_JSON_R5, json.readTree("""
                {"resourceType": "OperationOutcome",
                 "issue": [{"severity": "error", "code": "%s", "diagnostics": "%s"}]}"""
                .formatted(code, diagnostics)));
    }

    private HttpClient signedIn() throws Exception {
        return login("test-user", "test-password");
    }

    /** A session confined by a required password change, which holds no Permission. */
    private HttpClient confined() throws Exception {
        UUID connectorId = connectors.create("patient-error-" + UUID.randomUUID(), null).id();
        String token = connectors.issueToken(connectorId, TokenPermissions.ALL, null, null).presentedValue();
        String userName = "patient-confined-" + UUID.randomUUID();
        HttpResponse<String> created = send(HttpClient.newHttpClient(),
                request("/scim/v2/Users")
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/scim+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                                 "userName":"%s","password":"connector-chosen-1"}"""
                                .formatted(userName))));
        assertThat(created.statusCode()).isEqualTo(201);
        provisioned.add(json.readTree(created.body()).get("id").asText());
        return login(userName, "connector-chosen-1");
    }

    private HttpClient login(String username, String password) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> response = send(client, withCsrf(client, request("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", username, "password", password)))));
        assertThat(response.statusCode()).as("login of %s", username).isEqualTo(200);
        return client;
    }

    private HttpRequest.Builder withCsrf(HttpClient client, HttpRequest.Builder request)
            throws Exception {
        JsonNode csrf = json.readTree(send(client, request("/api/auth/csrf").GET()).body());
        return request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
    }

    private HttpRequest.Builder patient(String id) {
        return request("/api/patient/" + id);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
