package com.example.backend.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.backend.patient.InMemoryPatientStore;
import com.example.backend.patient.application.PatientReadService;
import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.domain.PatientStore;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.FhirOperationOutcome;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * {@code GET /api/patient/{id}} as the web adapter answers it, with the app-wide exception handler
 * its failures go to: an R5 {@code Patient} rendered from the stored columns, or an
 * {@code OperationOutcome} with fixed diagnostics for an unknown id, a malformed one and a fault.
 * The filter chain is not in this MockMvc: the Permission, its backstop, the {@code 403}, the
 * {@code 405} and what is logged are {@code PatientErrorContractIntegrationTests}', and the contract
 * {@code ApiContractFixtureTests}' and {@code AuthorizationContractTests}'.
 */
class PatientControllerTests {

    private static final UUID TAN_ID = UUID.fromString("0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a11");

    private static final Patient TAN_WEI_MING = new Patient(
            TAN_ID,
            "S1234567A",
            new PatientName("Tan Wei Ming", "Tan", List.of("Wei", "Ming")),
            AdministrativeGender.MALE,
            LocalDate.of(1985, 3, 12));

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    /** A store whose every read fails, with a message quoting what a driver's would. */
    private static final PatientStore FAILING = new PatientStore() {
        @Override
        public Optional<Patient> findById(UUID id) {
            throw new IllegalStateException(
                    "SELECT nric FROM patients WHERE id = '" + id + "' -- S1234567A");
        }

        @Override
        public boolean existsById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Patient patient) {
            throw new UnsupportedOperationException();
        }
    };

    private final JsonMapper json = JsonMapper.builder().build();

    private final InMemoryPatientStore patients = new InMemoryPatientStore();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = serving(patients);
    }

    private static MockMvc serving(PatientStore store) {
        return MockMvcBuilders
                .standaloneSetup(new PatientController(new PatientReadService(store)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpServletResponse read(UUID id) throws Exception {
        return read(id.toString());
    }

    private MockHttpServletResponse read(String id) throws Exception {
        return mvc.perform(get("/api/patient/{id}", id)).andReturn().getResponse();
    }

    private MockHttpServletResponse readFailing() throws Exception {
        return serving(FAILING).perform(get("/api/patient/{id}", TAN_ID)).andReturn().getResponse();
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

    @Test
    void aStoredPatientIsServedAsAnR5PatientResource() throws Exception {
        patients.given(TAN_WEI_MING);

        MockHttpServletResponse response = read(TAN_ID);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(json.readTree("""
                {
                  "resourceType": "Patient",
                  "id": "0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a11",
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

    @Test
    void thePatientIsServedAsFhirJsonOfVersionFive() throws Exception {
        patients.given(TAN_WEI_MING);

        assertThat(read(TAN_ID).getContentType()).isEqualTo(FHIR_JSON_R5);
    }

    @Test
    void aNameWithNoFamilyOrGivenPartLeavesBothOut() throws Exception {
        patients.given(new Patient(TAN_ID, "S7654321A", new PatientName("Suriani", null, List.of()),
                AdministrativeGender.FEMALE, LocalDate.of(1990, 7, 1)));

        assertThat(body(read(TAN_ID)).get("name")).isEqualTo(json.readTree("""
                [{"use": "official", "text": "Suriani"}]"""));
    }

    @ParameterizedTest
    @CsvSource({"MALE, male", "FEMALE, female", "OTHER, other", "UNKNOWN, unknown"})
    void theGenderIsServedAsItsFhirCode(AdministrativeGender gender, String code) throws Exception {
        patients.given(new Patient(TAN_ID, "S1234567A", TAN_WEI_MING.name(), gender,
                TAN_WEI_MING.birthDate()));

        assertThat(body(read(TAN_ID)).get("gender").asString()).isEqualTo(code);
    }

    @Test
    void anUnknownIdIsANotFoundOutcome() throws Exception {
        patients.given(TAN_WEI_MING);

        MockHttpServletResponse response = read(UUID.randomUUID());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response)).isEqualTo(outcome("not-found", "The resource was not found."));
    }

    @Test
    void anUnknownIdIsAnsweredAsFhirJsonOfVersionFive() throws Exception {
        assertThat(read(UUID.randomUUID()).getContentType()).isEqualTo(FHIR_JSON_R5);
    }

    /**
     * Whatever the id carries — a line break that would forge a log line, markup that would run
     * in a page — the answer is the one fixed outcome, which quotes nothing of it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "<img src=x onerror=alert(1)>", "1\r\nforged: S1234567A"})
    void aMalformedIdIsAnInvalidOutcomeThatQuotesNothingOfIt(String id) throws Exception {
        MockHttpServletResponse response = read(id);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).isEqualTo(outcome("invalid", "The request is not valid."));
    }

    @Test
    void aMalformedIdIsAnsweredAsFhirJsonOfVersionFive() throws Exception {
        assertThat(read("not-a-uuid").getContentType()).isEqualTo(FHIR_JSON_R5);
    }

    @Test
    void aFaultIsAnExceptionOutcomeThatSaysNothingOfIt() throws Exception {
        MockHttpServletResponse response = readFailing();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response))
                .isEqualTo(outcome("exception", "The request could not be completed."));
    }

    @Test
    void aFaultIsAnsweredAsFhirJsonOfVersionFive() throws Exception {
        assertThat(readFailing().getContentType()).isEqualTo(FHIR_JSON_R5);
    }

    /**
     * The path the web layer answers errors under as FHIR is this controller's own base: the two
     * are written in two modules, and a move of one without the other would answer this
     * controller's errors as {@code ApiError}s.
     */
    @Test
    void theControllersBaseIsTheFhirPathItsErrorsAreAnsweredUnder() {
        assertThat(PatientController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly(FhirOperationOutcome.FHIR_PATH);
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
