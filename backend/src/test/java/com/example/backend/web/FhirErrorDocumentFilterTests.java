package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a client receives when something inside the filter refuses a request with
 * {@code sendError}, as the firewall, the security chain and the dispatcher do: under the FHIR
 * path, an {@code OperationOutcome} chosen by the status alone; anywhere else, the refusal as it
 * was sent.
 */
class FhirErrorDocumentFilterTests {

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    private final JsonMapper json = JsonMapper.builder().build();

    private final FhirErrorDocumentFilter filter = new FhirErrorDocumentFilter();

    /** A servlet that sets {@code Allow}, then refuses with {@code status} and a quoting message. */
    private static HttpServlet refusing(int status) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException {
                response.setHeader("Allow", "GET,HEAD");
                response.sendError(status, "Request method 'POST' is not supported: S1234567A");
            }
        };
    }

    private MockHttpServletResponse refused(String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(refusing(status)));
        return response;
    }

    private JsonNode outcome(String code, String diagnostics) {
        return json.readTree("""
                {"resourceType": "OperationOutcome",
                 "issue": [{"severity": "error", "code": "%s", "diagnostics": "%s"}]}"""
                .formatted(code, diagnostics));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "400 | invalid       | The request is not valid.",
        "401 | login         | A signed-in session is required.",
        "403 | forbidden     | The request is not permitted.",
        "404 | not-found     | The resource was not found.",
        "405 | not-supported | The request is not supported.",
        "406 | not-supported | The request is not supported.",
        "415 | not-supported | The request is not supported.",
        "422 | invalid       | The request is not valid.",
        "500 | exception     | The request could not be completed.",
        "503 | exception     | The request could not be completed."})
    void aRefusalUnderTheFhirPathIsTheOutcomeForItsStatus(
            int status, String code, String diagnostics) throws Exception {
        MockHttpServletResponse response = refused("/api/patient/x", status);

        assertThat(json.readTree(response.getContentAsString()))
                .isEqualTo(outcome(code, diagnostics));
    }

    /**
     * The filter writes by hand what a handler's answer serializes from the record: the two
     * renderings are one document, whatever the status.
     */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 406, 415, 422, 500, 503})
    void theWrittenOutcomeIsTheRecordsOwnSerialization(int status) throws Exception {
        MockHttpServletResponse response = refused("/api/patient/x", status);

        assertThat(json.readTree(response.getContentAsString()))
                .isEqualTo(json.valueToTree(FhirOperationOutcome.of(status)));
    }

    @Test
    void theOutcomeIsAnsweredUnderTheRefusalsStatus() throws Exception {
        assertThat(refused("/api/patient/x", 405).getStatus()).isEqualTo(405);
    }

    @Test
    void theOutcomeIsFhirJsonOfVersionFive() throws Exception {
        assertThat(refused("/api/patient/x", 403).getContentType()).isEqualTo(FHIR_JSON_R5);
    }

    @Test
    void theOutcomeIsWrittenInsteadOfAnErrorDispatch() throws Exception {
        assertThat(refused("/api/patient/x", 405).getErrorMessage()).isNull();
    }

    @Test
    void theOutcomeIsTheWholeResponse() throws Exception {
        assertThat(refused("/api/patient/x", 405).isCommitted()).isTrue();
    }

    @Test
    void anythingWrittenBeforeTheRefusalIsDiscarded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patient/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res)
                    throws IOException {
                res.getOutputStream().write("{\"nric\":\"S1234567A\"".getBytes());
                res.sendError(500);
            }
        }));

        assertThat(response.getContentAsString()).isEqualTo(
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                        + "\"code\":\"exception\",\"diagnostics\":\"The request could not be completed.\"}]}");
    }

    @Test
    void aHeaderSetBeforeTheRefusalSurvives() throws Exception {
        assertThat(refused("/api/patient/x", 405).getHeader("Allow")).isEqualTo("GET,HEAD");
    }

    @Test
    void aRefusalWithoutAMessageIsTheOutcomeToo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patient/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res)
                    throws IOException {
                res.sendError(404);
            }
        }));

        assertThat(json.readTree(response.getContentAsString()))
                .isEqualTo(outcome("not-found", "The resource was not found."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/patient", "/api/patient/x"})
    void theFhirPathIsThePatientBaseAndEverythingUnderIt(String path) {
        assertThat(FhirOperationOutcome.coversPath(new MockHttpServletRequest("GET", path))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/patients", "/api/self", "/", "/api"})
    void noOtherPathIsTheFhirPath(String path) {
        assertThat(FhirOperationOutcome.coversPath(new MockHttpServletRequest("GET", path))).isFalse();
    }

    @Test
    void theFhirPathIsReadBelowTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/patient/x");
        request.setContextPath("/app");

        assertThat(FhirOperationOutcome.coversPath(request)).isTrue();
    }

    @Test
    void aRefusalAnywhereElseIsLeftAsItWasSent() throws Exception {
        MockHttpServletResponse response = refused("/api/self", 405);

        assertThat(response.getErrorMessage())
                .isEqualTo("Request method 'POST' is not supported: S1234567A");
    }
}
