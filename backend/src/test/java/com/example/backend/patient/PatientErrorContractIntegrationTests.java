package com.example.backend.patient;

import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ServerSession;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.epic.EpicLoginOnContext;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.scim.application.ConnectorAdministrationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

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
 * <p>The patient is the patient source's Tan Wei Ming, read through the mock every context reads
 * ({@code PatientSourceStubs}), serving the local mock's own stub files: no other record names
 * his NRIC, his name or his birth date, so one of them found in the stream can only have come
 * from the read. A source failure is each of the stub files' fault stubs, whose dummy values
 * likewise appear nowhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class PatientErrorContractIntegrationTests extends EpicLoginOnContext {

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    private static final String NRIC = "S1234567A";

    private static final String NAME_TEXT = "Tan Wei Ming";

    private static final String BIRTH_DATE = "1985-03-12";

    /** One character past the FHIR id grammar's 64, and nothing else wrong with it. */
    private static final String TOO_LONG =
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    private static final String REQUEST_RECORD = "HTTP request completed";

    /** Tan Wei Ming's FHIR id, as the patient source's stub files serve it. */
    private final String id = "eq081-VQEgP8drUUqCWzHfw3";

    private final List<String> provisioned = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectorAdministrationService connectors;

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
        provisioned.forEach(user -> jdbc.update(
                "DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL",
                UUID.fromString(user)));
    }

    // ---- the error contract --------------------------------------------------------------

    /**
     * One character past FHIR's 64, markup, an encoded line break, an empty segment and encoded
     * slashes in place of the id. The line break, the empty segment and the encoded slashes never
     * reach the dispatcher — the request firewall refuses them — and are answered with the same
     * outcome. The encoded slashes reach the firewall at all only because Tomcat passes them
     * through rather than refusing them with its own error page ({@code EncodedSlashPassthrough}).
     */
    @ParameterizedTest
    @ValueSource(strings = {TOO_LONG, "%3Cimg%20src=x%20onerror=alert(1)%3E",
        "1%0D%0Aforged:%20S1234567A", "a//b", "a%2F%2Fb", "a%2fb"})
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

        HttpResponse<String> response = send(session, ServerSession.withCsrf(session, port, patient(id))
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
                .doesNotContain(NRIC, NAME_TEXT, "Wei Ming", BIRTH_DATE,
                        id, unknown, malformed);
    }

    /**
     * Each fault stub the stub files serve, by the failure its record names and the
     * {@code error.category} it is reported under: no answer within the read timeout, a
     * {@code 5xx}, and a Patient breaking each domain rule. The slow stub waits past the
     * production read timeout, so its cases take that long.
     */
    private static final String FAULT_STUBS = """
            fault-slow,             timed-out,        network
            fault-server-error,     server-error,     server
            fault-no-nric,          no-nric,          data
            fault-no-official-name, no-official-name, data
            fault-no-name-text,     no-name-text,     data
            fault-no-gender,        no-gender,        data
            fault-no-birth-date,    no-birth-date,    data
            """;

    /** The table above holds every fault stub the stub files serve. */
    @Test
    void theFaultTableHoldsEveryFaultStub() {
        assertThat(FAULT_STUBS.lines().map(row -> row.split(",")[0].strip()))
                .containsExactlyInAnyOrderElementsOf(faultStubIds().toList());
    }

    /** Every fault stub's id, as the stub files serve it. */
    static Stream<String> faultStubIds() {
        return PatientSourceStubs.faults().stream().map(PatientSourceStubs.FaultStub::id);
    }

    /** The patient source failing is the {@code 502} outcome, whatever way it fails. */
    @ParameterizedTest
    @MethodSource("faultStubIds")
    void aSourceFailureIsTheBadGatewayOutcome(String failing) throws Exception {
        HttpResponse<String> response = send(signedIn(), patient(failing).GET());

        assertThat(answer(response))
                .isEqualTo(answer(502, "exception", "The request could not be completed."));
    }

    /**
     * A source failure writes exactly one {@code ERROR}, the fault's record, naming the failure or
     * the broken rule under its category; and nothing in the stream names the id asked for, or
     * anything the source's answer carried — its NRIC, its names, its birth date, its error body.
     */
    @ParameterizedTest
    @CsvSource(textBlock = FAULT_STUBS)
    void aSourceFailureIsOneFaultRecordNamingItAndNothingOfThePatient(String failing,
            String reason, String category) throws Exception {
        PatientSourceStubs.FaultStub stub = PatientSourceStubs.faults().stream()
                .filter(fault -> fault.id().equals(failing)).findFirst().orElseThrow();
        HttpClient session = signedIn();
        logs.reset();

        send(session, patient(failing).GET());
        logs.awaitRequestRecords(1);

        assertThat(logs.records())
                .filteredOn(record -> "ERROR".equals(record.at("/log/level").asText()))
                .singleElement()
                .satisfies(fault -> assertThat(List.of(
                        fault.at("/app/event/action").asText(),
                        fault.at("/event/reason").asText(),
                        fault.at("/error/category").asText()))
                        .containsExactly("http.request.fault", reason, category));
        assertThat(stub.patientValues()).isNotEmpty();
        assertThat(logs.lines()).contains("http.request.fault")
                .doesNotContain(failing)
                .doesNotContain(stub.patientValues());
    }

    // ---- harness -------------------------------------------------------------------------

    /** What a client reads off a response: its status, its media type and its body. */
    private record Answer(int status, String contentType, JsonNode body) {
    }

    private Answer answer(HttpResponse<String> response) {
        return new Answer(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null).replace(" ", ""),
                JSON.readTree(response.body()));
    }

    private Answer answer(int status, String code, String diagnostics) {
        return new Answer(status, FHIR_JSON_R5, JSON.readTree("""
                {"resourceType": "OperationOutcome",
                 "issue": [{"severity": "error", "code": "%s", "diagnostics": "%s"}]}"""
                .formatted(code, diagnostics)));
    }

    private HttpClient signedIn() throws Exception {
        return ServerSession.logIn(port, "test-user", "test-password");
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
        provisioned.add(JSON.readTree(created.body()).get("id").asText());
        return ServerSession.logIn(port, userName, "connector-chosen-1");
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
