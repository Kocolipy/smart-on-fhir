package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.observability.RequestIdFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Epic Login switch, off — which is what a deployment gets by setting nothing.
 *
 * <p>This context is the whole application with <strong>no Epic variable at all</strong>: the
 * test configuration names none, so it starting is itself the proof that a deployment which
 * never heard of Epic Login needs none of its settings. While the switch is off every path under
 * {@code /api/auth/epic/}, the routes later steps add included, is simply not there — a
 * {@code 404}, not the {@code 401} or {@code 403} the application chain's deny-by-default rule
 * would give an unknown route, and not the single-page shell.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class EpicReleaseGateIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/auth/epic/launch",
        "/api/auth/epic/authorize",
        "/api/auth/epic/callback",
        "/api/auth/epic/jwks.json",
        "/api/auth/epic/anything",
        "/api/auth/epic/",
        "/api/auth/epic",
    })
    void withTheSwitchOffEveryEpicRouteIsNotFound(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).queryParam("iss", "https://fhir.example.org"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    /** Ahead of the CSRF check too: a write is not refused for its token but simply not served. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/epic/launch", "/api/auth/epic/callback"})
    void withTheSwitchOffAPostIsNotFoundEither(String path) throws Exception {
        MvcResult result = mvc.perform(post(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    /** And never the single-page application's HTML shell, which would read as a {@code 200}. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/epic/launch", "/api/auth/epic/anything/at/all"})
    void aGatedPathNeverFallsThroughToTheSinglePageShell(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("<html")
                .doesNotContain("<!doctype");
    }
}
