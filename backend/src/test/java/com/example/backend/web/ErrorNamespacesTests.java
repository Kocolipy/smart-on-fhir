package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which namespace, and so which error document, a request's path selects — the most specific
 * namespace covering it — and the answer {@link ErrorNamespaces#refuse} writes for it.
 */
class ErrorNamespacesTests {

    private final JsonMapper json = JsonMapper.builder().build();

    private final ErrorNamespace scim = named("/scim/v2");

    private final ErrorNamespaces namespaces = new ErrorNamespaces(List.of(scim));

    private static ErrorNamespace named(String path) {
        return new ErrorNamespace() {
            @Override
            public String path() {
                return path;
            }

            @Override
            public void refuse(HttpServletResponse response, int status) throws IOException {
                response.setStatus(status);
                response.getWriter().write(path + " " + status);
            }
        };
    }

    @ParameterizedTest
    @CsvSource({
        "/api/patient,           /api/patient",
        "/api/patient/x,         /api/patient",
        "/api/patient/x/history, /api/patient",
        "/api,                   /api",
        "/api/admin/connectors,  /api",
        "/api/patients,          /api",
        "/api/auth/epic/launch,  /api",
        "/scim/v2,               /scim/v2",
        "/scim/v2/Users,         /scim/v2",
        // Decoded, as Spring routes on it: these reach the same handlers.
        "/%61pi/self,            /api",
        "/api/%70atient/x,       /api/patient",
        "/scim/%762/Users,       /scim/v2"})
    void thePathSelectsTheMostSpecificNamespaceCoveringIt(String path, String namespace) {
        assertThat(namespaces.of(new MockHttpServletRequest("GET", path)))
                .get().extracting(ErrorNamespace::path).isEqualTo(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/apix", "/assets/app.js", "/scim", "/scim/v1/Users",
        "/actuator/health"})
    void aPathNoNamespaceCoversSelectsNone(String path) {
        assertThat(namespaces.of(new MockHttpServletRequest("GET", path))).isEmpty();
    }

    @Test
    void thePathIsReadBelowTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/patient/x");
        request.setContextPath("/app");

        assertThat(namespaces.of(request)).contains(ErrorNamespaces.FHIR);
    }

    @Test
    void theApplicationApisNamespacesAreAlwaysPresent() {
        assertThat(new ErrorNamespaces(List.of()).all())
                .containsExactly(ErrorNamespaces.FHIR, ErrorNamespaces.API);
    }

    @Test
    void twoNamespacesCannotClaimOnePath() {
        assertThatThrownBy(() -> new ErrorNamespaces(List.of(named("/api"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/api");
    }

    @Test
    void aRefusalInANamespaceIsItsDocumentCommittedInPlaceOfAnythingBuffered() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("partial".getBytes());

        namespaces.refuse(new MockHttpServletRequest("GET", "/api/admin/accounts"), response, 403);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(json.readTree(response.getContentAsString())).isEqualTo(json.readTree(
                "{\"status\":403,\"code\":\"request-refused\",\"detail\":\"The request was refused.\"}"));
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    void aRefusalInAContributedNamespaceIsThatNamespacesDocument() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        namespaces.refuse(new MockHttpServletRequest("GET", "/scim/v2/Users"), response, 404);

        assertThat(response.getContentAsString()).isEqualTo("/scim/v2 404");
        assertThat(response.isCommitted()).isTrue();
    }

    @Test
    void aRefusalInNoNamespaceIsTheContainersOwn() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        namespaces.refuse(new MockHttpServletRequest("GET", "/login"), response, 401);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.isCommitted()).as("sendError commits it").isTrue();
    }

    @Test
    void theApiNamespacesDocumentIsTheRecordsOwnSerialization() throws Exception {
        for (int status : new int[] {400, 401, 403, 404, 405, 415, 500, 503}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ErrorNamespaces.API.refuse(response, status);

            assertThat(json.readTree(response.getContentAsString())).as("status %d", status)
                    .isEqualTo(json.valueToTree(ApiError.of(
                            org.springframework.http.HttpStatusCode.valueOf(status))));
            assertThat(response.getStatus()).isEqualTo(status);
        }
    }
}
