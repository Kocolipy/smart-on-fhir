package com.example.backend.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import com.example.backend.patient.InMemoryPatientStore;
import com.example.backend.patient.application.DevPatientFixtures;
import com.example.backend.patient.application.PatientReadService;
import com.example.backend.patient.application.PatientSeedService;
import com.example.backend.patient.domain.Patient;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.ErrorNamespaces;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The development patient fixtures as {@code GET /api/patient/{id}} serves them, held against base
 * FHIR R5 by HAPI FHIR's validator (test scope only; ADR 0014): every fixture is readable once
 * seeded, every response is a valid base R5 {@code Patient}, and the {@code 404} is a valid base R5
 * {@code OperationOutcome}. The JSON validated is the response body exactly as written, not a
 * HAPI model rebuilt from it.
 */
class PatientR5ConformanceTests {

    private static final String BASE_PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";

    private static final String BASE_OPERATION_OUTCOME =
            "http://hl7.org/fhir/StructureDefinition/OperationOutcome";

    /** Building the validator loads every R5 definition, so the class builds it once. */
    private static FhirValidator validator;

    private final JsonMapper json = JsonMapper.builder().build();

    private final InMemoryPatientStore patients = new InMemoryPatientStore();

    private MockMvc mvc;

    @BeforeAll
    static void buildTheBaseR5Validator() {
        FhirContext r5 = FhirContext.forR5();
        ValidationSupportChain support = new ValidationSupportChain(
                new DefaultProfileValidationSupport(r5),
                new CommonCodeSystemsTerminologyService(r5),
                new InMemoryTerminologyServerValidationSupport(r5),
                new SnapshotGeneratingValidationSupport(r5));
        validator = r5.newValidator().registerValidatorModule(new FhirInstanceValidator(support));
    }

    @BeforeEach
    void seedTheFixturesBehindTheReadEndpoint() {
        new PatientSeedService(patients).seedDevFixtures(DevPatientFixtures.ALL);
        mvc = MockMvcBuilders
                .standaloneSetup(new PatientController(new PatientReadService(patients)))
                // A failure is answered by the app-wide handler, as in the running service.
                .setControllerAdvice(new ApiExceptionHandler(new ErrorNamespaces(List.of())))
                .build();
    }

    private MockHttpServletResponse read(String id) throws Exception {
        return mvc.perform(get("/api/patient/" + id)).andReturn().getResponse();
    }

    private static List<String> errors(String body, String profile) {
        return validator.validateWithResult(body, new ValidationOptions().addProfile(profile))
                .getMessages().stream()
                .filter(message -> message.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal())
                .map(SingleValidationMessage::toString)
                .toList();
    }

    /** Every fixture's id, as the read endpoint's path takes it. */
    static Stream<String> fixtureIds() {
        return DevPatientFixtures.ALL.stream().map(Patient::id).map(UUID::toString);
    }

    @ParameterizedTest
    @MethodSource("fixtureIds")
    void everyFixtureIsReadableOnceSeeded(String id) throws Exception {
        assertThat(read(id).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("fixtureIds")
    void everyFixtureIsServedAsAValidBaseR5Patient(String id) throws Exception {
        assertThat(errors(read(id).getContentAsString(), BASE_PATIENT)).isEmpty();
    }

    @Test
    void theNotFoundAnswerIsAValidBaseR5OperationOutcome() throws Exception {
        MockHttpServletResponse response = read(UUID.randomUUID().toString());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(errors(response.getContentAsString(), BASE_OPERATION_OUTCOME)).isEmpty();
    }

    @Test
    void theInvalidIdAnswerIsAValidBaseR5OperationOutcome() throws Exception {
        MockHttpServletResponse response = read("not-a-uuid");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(errors(response.getContentAsString(), BASE_OPERATION_OUTCOME)).isEmpty();
    }

    /** The passes above mean something only if the validator fails what is not base R5. */
    @Test
    void theValidatorFailsAPatientThatIsNotBaseR5() {
        assertThat(errors("""
                {"resourceType": "Patient", "gender": "M", "birthDate": "12/03/1985",
                 "name": [{"use": "official", "family": ["Tan"]}]}""", BASE_PATIENT))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    /**
     * Each fixture's name as served: {@code given} is the stored given names split on spaces,
     * and a part the fixture does not hold is left out rather than sent empty — so the single
     * name has no {@code family}, and the unsplit Malay name neither part.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        00000000-0000-4000-8000-00000000c001 | {"use":"official","text":"Tan Wei Ming","family":"Tan","given":["Wei","Ming"]}
        00000000-0000-4000-8000-00000000c002 | {"use":"official","text":"Siti Nurhaliza binte Ahmad"}
        00000000-0000-4000-8000-00000000c003 | {"use":"official","text":"Kumaravel","given":["Kumaravel"]}
        00000000-0000-4000-8000-00000000c004 | {"use":"official","text":"Marcus Anthony de Souza","family":"de Souza","given":["Marcus","Anthony"]}
        """)
    void everyFixturesNameIsServedWithOnlyThePartsItHolds(String id, String name) throws Exception {
        JsonNode body = json.readTree(read(id).getContentAsString());

        assertThat(body.get("name")).isEqualTo(json.readTree("[" + name + "]"));
    }
}
