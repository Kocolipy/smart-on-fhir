package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a client receives when something inside the filter refuses a request with
 * {@code sendError}, as the firewall and the dispatcher do: under the FHIR namespace, an
 * {@code OperationOutcome} chosen by the status alone; under the rest of the application API, an
 * {@link ApiError} chosen the same way; in a namespace another module contributes, that
 * namespace's document; in none, the refusal as it was sent.
 */
class ErrorDocumentFilterTests {

    private static final String FHIR_JSON_R5 = "application/fhir+json;fhirVersion=5.0";

    private final JsonMapper json = JsonMapper.builder().build();

    /** A namespace another module contributes, whose document names the status it was given. */
    private final ErrorNamespace contributed = new ErrorNamespace() {
        @Override
        public String path() {
            return "/elsewhere/v1";
        }

        @Override
        public void refuse(HttpServletResponse response, int status) throws IOException {
            response.setStatus(status);
            response.getWriter().write("contributed " + status);
        }
    };

    private final ErrorDocumentFilter filter =
            new ErrorDocumentFilter(new ErrorNamespaces(List.of(contributed)));

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

    /**
     * Under the rest of the application API, the {@link ApiError} for the status, as
     * {@code application/json} with no charset — the bytes a handler's answer serializes — and
     * nothing of the message, which quotes the request.
     */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 406, 415, 422, 500, 503})
    void aRefusalUnderTheApiIsTheApiErrorForItsStatus(int status) throws Exception {
        MockHttpServletResponse response = refused("/api/admin/accounts", status);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(json.readTree(response.getContentAsString()))
                .isEqualTo(json.valueToTree(ApiError.of(HttpStatusCode.valueOf(status))));
        assertThat(response.getContentAsString()).doesNotContain("S1234567A", "POST");
        assertThat(response.getErrorMessage()).isNull();
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getHeader("Allow")).isEqualTo("GET,HEAD");
    }

    @Test
    void aRefusalInAContributedNamespaceIsThatNamespacesDocument() throws Exception {
        MockHttpServletResponse response = refused("/elsewhere/v1/Things", 405);

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentAsString()).isEqualTo("contributed 405");
        assertThat(response.getErrorMessage()).isNull();
        assertThat(response.isCommitted()).isTrue();
    }

    @Test
    void anErrorAfterTheResponseIsCommittedIsRefusedAsTheServletApiRequires() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/self");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            res.flushBuffer();
            assertThatThrownBy(() -> ((HttpServletResponse) res).sendError(404))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ((HttpServletResponse) res).sendError(404, "x"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void aRefusalInNoNamespaceIsLeftAsItWasSent() throws Exception {
        MockHttpServletResponse response = refused("/assets/app.js", 405);

        assertThat(response.getErrorMessage())
                .isEqualTo("Request method 'POST' is not supported: S1234567A");
    }
}
