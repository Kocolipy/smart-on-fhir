package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The gate — the whole of Epic Login while it is off — decides only for its own namespace.
 * Everything else reaches the rest of the chain untouched, which
 * {@code EpicReleaseGateIntegrationTests}, holding the closed case against the real chain, cannot
 * show on its own. While Epic Login is on there is no gate in the chain at all; that its routes
 * are then reached is the Epic Login integration suites'.
 */
class EpicReleaseGateFilterTests {

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/epicx", "/api/auth/me", "/showcase", "/"})
    void aClosedGatePassesEveryOtherPathThrough(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        new EpicReleaseGateFilter()
                .doFilter(new MockHttpServletRequest("GET", path),
                        new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request reached the rest of the chain").isNotNull();
    }

    @Test
    void aClosedGateAnswersAnEpicRouteItselfWithNotFound() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EpicReleaseGateFilter()
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

        new EpicReleaseGateFilter()
                .doFilter(new MockHttpServletRequest("GET", "/api/auth/epic/launch"), response,
                        new MockFilterChain());

        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"status\":404,\"code\":\"request-refused\",\"detail\":\"The request was refused.\"}");
    }
}
