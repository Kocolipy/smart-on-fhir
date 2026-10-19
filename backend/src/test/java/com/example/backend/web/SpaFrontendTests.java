package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.ModelAndView;

/**
 * That each adapter asks {@link SpaRoutes}, through the real servlet stack. The
 * path algebra itself — prefixes, exact matches, the file-request heuristic — is
 * covered by {@link SpaRoutesTests} without paying for a Spring context, so each
 * case here is one representative rather than a matrix.
 */
@SpringBootTest
@Import(com.example.backend.ContainerTestConfiguration.class)
class SpaFrontendTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private SpaErrorViewResolver errorViewResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void servesFrontendEntryPointWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    /**
     * The filter chain consults SpaRoutes, so the public-GET allowance does not
     * swallow an API path. "/api" itself is kept alongside a nested path because
     * a regression on the exact match is a security hole, not a 404.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/self"})
    void keepsApiRoutesProtected(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
    }

    /** Unmatched routes belong to the client-side router. */
    @Test
    void forwardsMissingClientSideRoutesToFrontendEntryPoint() {
        ModelAndView resolved = resolve("/dashboard", HttpStatus.NOT_FOUND);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getViewName()).isEqualTo("forward:/index.html");
    }

    /** A missing file stays a 404 rather than returning the HTML shell. */
    @Test
    void doesNotForwardPathsThatLookLikeFiles() {
        assertThat(resolve("/assets/missing.js", HttpStatus.NOT_FOUND)).isNull();
    }

    /** Reserved prefixes answer for themselves. */
    @Test
    void doesNotForwardReservedServerPaths() {
        assertThat(resolve("/api/missing", HttpStatus.NOT_FOUND)).isNull();
    }

    /** The shell stands in for a missing route only, not for a failed one. */
    @Test
    void doesNotForwardErrorsOtherThanNotFound() {
        assertThat(resolve("/dashboard", HttpStatus.INTERNAL_SERVER_ERROR)).isNull();
    }

    @Test
    void doesNotForwardWhenTheErrorCarriesNoPath() {
        assertThat(errorViewResolver.resolveErrorView(
                new MockHttpServletRequest(), HttpStatus.NOT_FOUND, Map.of()))
                .isNull();
    }

    private ModelAndView resolve(String path, HttpStatus status) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, path);
        return errorViewResolver.resolveErrorView(request, status, Map.of());
    }
}
