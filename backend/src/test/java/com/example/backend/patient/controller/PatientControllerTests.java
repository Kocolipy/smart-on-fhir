package com.example.backend.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.patient.InMemoryPatientStore;
import com.example.backend.patient.application.PatientReadService;
import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/patient/{id}} as the web adapter answers it: an R5 {@code Patient} rendered from
 * the stored columns, or a {@code not-found} {@code OperationOutcome}. The filter chain is not in
 * this MockMvc; the Permission, its backstop and the contract are {@code ApiContractFixtureTests}'
 * and {@code AuthorizationContractTests}'.
 */
class PatientControllerTests {

    private static final UUID TAN_ID = UUID.fromString("0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a11");

    private static final Patient TAN_WEI_MING = new Patient(
            TAN_ID,
            "S1234567A",
            new PatientName("Tan Wei Ming", "Tan", List.of("Wei", "Ming")),
            AdministrativeGender.MALE,
            LocalDate.of(1985, 3, 12));

    private final JsonMapper json = JsonMapper.builder().build();

    private final InMemoryPatientStore patients = new InMemoryPatientStore();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new PatientController(new PatientReadService(patients)))
                .build();
    }

    private MockHttpServletResponse read(UUID id) throws Exception {
        return mvc.perform(get("/api/patient/" + id)).andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
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

        assertThat(read(TAN_ID).getContentType())
                .isEqualTo("application/fhir+json;fhirVersion=5.0");
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
    void anUnknownIdIsANotFoundOperationOutcome() throws Exception {
        patients.given(TAN_WEI_MING);

        MockHttpServletResponse response = read(UUID.randomUUID());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/fhir+json;fhirVersion=5.0");
        assertThat(body(response)).isEqualTo(json.readTree("""
                {
                  "resourceType": "OperationOutcome",
                  "issue": [
                    {"severity": "error", "code": "not-found",
                     "diagnostics": "No patient is held under this id."}
                  ]
                }"""));
    }
}
