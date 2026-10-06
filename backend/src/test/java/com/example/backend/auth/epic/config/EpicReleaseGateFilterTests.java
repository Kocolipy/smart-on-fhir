package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.epic.EpicReleaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The gate decides only for its own namespace, and only while closed. Everything else reaches the
 * rest of the chain untouched — which {@code EpicReleaseGateIntegrationTests}, holding the closed
 * case against the real chain, cannot show on its own.
 */
class EpicReleaseGateFilterTests {

    private static final EpicReleaseGate OPEN = new EpicReleaseGate(true);

    private static final EpicReleaseGate CLOSED = new EpicReleaseGate(false);

    @Test
    void anOpenGatePassesAnEpicRouteThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        new EpicReleaseGateFilter(OPEN)
                .doFilter(new MockHttpServletRequest("GET", "/api/auth/epic/launch"),
                        new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request reached the rest of the chain").isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/epicx", "/api/auth/me", "/showcase", "/"})
    void aClosedGatePassesEveryOtherPathThrough(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        new EpicReleaseGateFilter(CLOSED)
                .doFilter(new MockHttpServletRequest("GET", path),
                        new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request reached the rest of the chain").isNotNull();
    }

    @Test
    void aClosedGateAnswersAnEpicRouteItselfWithNotFound() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EpicReleaseGateFilter(CLOSED)
                .doFilter(new MockHttpServletRequest("GET", "/api/auth/epic/launch"), response, chain);

        assertThat(chain.getRequest()).as("nothing behind the gate ran").isNull();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /**
     * The answer is the application API's generic refusal as JSON — never empty, and never
     * anything the single-page shell could be mistaken for.
     */
    @Test
    void aClosedGateAnswersWithTheApiRefusalBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EpicReleaseGateFilter(CLOSED)
                .doFilter(new MockHttpServletRequest("GET", "/api/auth/epic/launch"), response,
                        new MockFilterChain());

        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"status\":404,\"code\":\"request-refused\",\"detail\":\"The request was refused.\"}");
    }
}
