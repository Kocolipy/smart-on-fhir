package com.example.backend.patient.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogEvent;
import com.example.backend.patient.PatientSourceStubs;
import com.example.backend.patient.application.PatientReadService;
import com.example.backend.patient.domain.PatientStore;
import com.example.backend.patient.infrastructure.http.PatientSourceAdapters;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.ErrorNamespaces;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/patient/{id}} as the web adapter answers it, reading the patient source's mock
 * through the HTTP adapter — the stub files the local mock serves ({@link PatientSourceStubs}) —
 * with the app-wide exception handler its failures go to: an R5 {@code Patient} copied from the
 * source's R4 one, or an {@code OperationOutcome} with fixed diagnostics for an unknown id, a
 * malformed one, a fault of the source ({@code 502}) and a fault on this side ({@code 500}). The filter chain is not in this MockMvc: the Permission, its
 * backstop, the {@code 403}, the {@code 405} and what is logged are
 * {@code PatientErrorContractIntegrationTests}', and the contract {@code ApiContractFixtureTests}'
 * and {@code AuthorizationContractTests}'. What the adapter makes of each answer of the source is
 * {@code PatientSourceAdapterContractTests}'.
 */
class PatientControllerTests {

    @RegisterExtension
    static final WireMockExtension SOURCE = PatientSourceStubs.extension();

    /** Tan Wei Ming's id, the Epic sandbox id the fixture borrows: letters, digits, a hyphen. */
    private static final String TAN_ID = "eq081-VQEgP8drUUqCWzHfw3";

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    /** A store whose every read fails, with a message quoting what a client's would. */
    private static final PatientStore FAILING = id -> {
        throw new IllegalStateException(
                "I/O error on GET request for \"http://source/Patient/" + id + "\" -- S1234567A");
    };

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = serving(PatientSourceAdapters.reading(SOURCE.baseUrl()));
    }

    private static MockMvc serving(PatientStore store) {
        return MockMvcBuilders
                .standaloneSetup(new PatientController(new PatientReadService(store)))
                .setControllerAdvice(new ApiExceptionHandler(new ErrorNamespaces(List.of())))
                .build();
    }

    private MockHttpServletResponse read(String id) throws Exception {
        return mvc.perform(request(HttpMethod.GET, "/api/patient/{id}", id))
                .andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    /** The one-issue outcome every error is answered with. */
    private JsonNode outcome(String code, String diagnostics) {
        return json.readTree("""
                {"resourceType": "OperationOutcome",
                 "issue": [{"severity": "error", "code": "%s", "diagnostics": "%s"}]}"""
                .formatted(code, diagnostics));
    }

    /**
     * Exactly the five elements, though the source's R4 Patient carries more — a {@code meta}, an
     * MRN identifier ahead of the NRIC, a {@code usual} name ahead of the official one,
     * {@code active} and a {@code telecom}.
     */
    @Test
    void aPatientIsServedAsAnR5PatientResourceOfItsFiveElements() throws Exception {
        MockHttpServletResponse response = read(TAN_ID);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(FHIR_JSON_R5);
        assertThat(body(response)).isEqualTo(json.readTree("""
                {
                  "resourceType": "Patient",
                  "id": "eq081-VQEgP8drUUqCWzHfw3",
                  "identifier": [
                    {"system": "https://fhir.synapxe.sg/NamingSystem/nric", "value": "S1234567A"}
                  ],
                  "name": [
                    {"use": "official", "text": "Tan Wei Ming", "family": "Tan",
                     "given": ["Wei", "Ming"]}
                  ],
                  "gender": "male",
                  "birthDate": "1985-03-12"
                }"""));
    }

    /** The Malay fixture's name is not split at all. */
    @Test
    void aNameWithNoFamilyOrGivenPartLeavesBothOut() throws Exception {
        assertThat(body(read("erXuFYUfucBZaryVksYEcMg3")).get("name")).isEqualTo(json.readTree("""
                [{"use": "official", "text": "Siti Nurhaliza binte Ahmad"}]"""));
    }

    /** Between them the fixtures carry every gender code. */
    @ParameterizedTest
    @CsvSource({"eq081-VQEgP8drUUqCWzHfw3, male", "erXuFYUfucBZaryVksYEcMg3, female",
        "egqBHVfQlt4Bw3XGXoxVxHg3, other", "e0w0LEDCYtfckT6N.CkJKCw3, unknown"})
    void theGenderIsServedAsItsFhirCode(String id, String code) throws Exception {
        assertThat(body(read(id)).get("gender").asString()).isEqualTo(code);
    }

    /**
     * A well-formed id the source holds no patient under — a UUID among them, and
     * {@code not-a-uuid}, which is a FHIR id like any other: the source's {@code 404}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a11",
        "eq081-VQEgP8drUUqCWzHfw4"})
    void anUnknownIdIsANotFoundOutcome(String unknown) throws Exception {
        MockHttpServletResponse response = read(unknown);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo(FHIR_JSON_R5);
        assertThat(body(response)).isEqualTo(outcome("not-found", "The resource was not found."));
    }

    /**
     * Whatever the id carries — one character past FHIR's 64, a line break that would forge a log
     * line, markup that would run in a page, a space or an underscore — the answer is the one fixed
     * outcome, which quotes nothing of it.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "eq081-VQEgP8drUUqCWzHfw3eq081-VQEgP8drUUqCWzHfw3eq081-VQEgP8drUUq",
        "<img src=x onerror=alert(1)>", "1\r\nforged: S1234567A", "Tan Wei Ming", "Tan_Wei_Ming"})
    void aMalformedIdIsAnInvalidOutcomeThatQuotesNothingOfIt(String id) throws Exception {
        MockHttpServletResponse response = read(id);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo(FHIR_JSON_R5);
        assertThat(body(response)).isEqualTo(outcome("invalid", "The request is not valid."));
    }

    /** The id grammar is checked before the source is called: a malformed id never reaches it. */
    @Test
    void aMalformedIdIsRefusedWithoutCallingTheSource() throws Exception {
        read("x".repeat(65));

        SOURCE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aFaultIsAnExceptionOutcomeThatSaysNothingOfIt() throws Exception {
        MockHttpServletResponse response = serving(FAILING)
                .perform(request(HttpMethod.GET, "/api/patient/{id}", TAN_ID))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo(FHIR_JSON_R5);
        assertThat(body(response))
                .isEqualTo(outcome("exception", "The request could not be completed."));
    }

    /**
     * A source answering anything but a Patient or a {@code 404} is a fault of the source, not "no
     * patient": the {@code 502} {@code exception} outcome.
     */
    @Test
    void aSourceFailureIsTheBadGatewayExceptionOutcome() throws Exception {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + TAN_ID))
                .willReturn(aResponse().withStatus(503).withBody("S1234567A Tan Wei Ming")));

        MockHttpServletResponse response = read(TAN_ID);

        assertThat(List.of(response.getStatus(), response.getContentType(), body(response)))
                .containsExactly(502, FHIR_JSON_R5,
                        outcome("exception", "The request could not be completed."));
    }

    /**
     * A source nothing answers on — no fault stub can serve this one — is the same {@code 502}
     * outcome, and its one {@code ERROR} names the failure, {@code unreachable}, and neither the
     * id asked for nor where the source is.
     */
    @Test
    void anUnreachableSourceIsTheBadGatewayOutcomeWithOneFaultRecordNamingIt() throws Exception {
        String base = closedPort();
        try (CapturedLog logs = CapturedLog.attach()) {
            MockHttpServletResponse response = serving(PatientSourceAdapters.reading(base))
                    .perform(request(HttpMethod.GET, "/api/patient/{id}", TAN_ID))
                    .andReturn().getResponse();

            assertThat(List.of(response.getStatus(), response.getContentType(), body(response)))
                    .containsExactly(502, FHIR_JSON_R5,
                            outcome("exception", "The request could not be completed."));
            assertThat(logs.withAction(Level.TRACE, LogEvent.KIND, "event"))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(List.of(record.getLevel(),
                                CapturedLog.fields(record).get(LogEvent.REASON),
                                CapturedLog.fields(record).get(LogEvent.ERROR_CATEGORY)))
                                .containsExactly(Level.ERROR, "unreachable", "network");
                        assertThat(printed(record)).doesNotContain(TAN_ID, base);
                    });
        }
    }

    /** Everything a record prints: its message, its fields and its exception's messages. */
    private static String printed(ILoggingEvent record) {
        StringBuilder text = new StringBuilder(record.getFormattedMessage())
                .append(CapturedLog.fields(record)).append(record.getMDCPropertyMap());
        for (IThrowableProxy link = record.getThrowableProxy(); link != null;
                link = link.getCause()) {
            text.append(link.getClassName()).append(link.getMessage());
        }
        return text.toString();
    }

    /** A base URL nothing listens on: a mock's, once it has stopped. */
    private static String closedPort() {
        WireMockServer stopped = new WireMockServer(PatientSourceStubs.options());
        stopped.start();
        String base = stopped.baseUrl();
        stopped.stop();
        return base;
    }

    /**
     * The path the web layer answers errors under as FHIR is this controller's own base: the two
     * are written in two modules, and a move of one without the other would answer this
     * controller's errors as {@code ApiError}s.
     */
    @Test
    void theControllersBaseIsTheFhirPathItsErrorsAreAnsweredUnder() {
        assertThat(PatientController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly(ErrorNamespaces.FHIR_PATH);
    }

    /**
     * Refused by the dispatcher before the read runs: against a store whose every read fails, a
     * method that reached the read would be a {@code 500}. Its body is the chain's to write
     * ({@code PatientErrorContractIntegrationTests}).
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aMethodOtherThanAReadIsRefusedBeforeTheReadRuns(String method) throws Exception {
        assertThat(serving(FAILING)
                .perform(request(HttpMethod.valueOf(method), "/api/patient/{id}", TAN_ID))
                .andReturn().getResponse().getStatus())
                .isEqualTo(405);
    }
}
