package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.config.ScimReleaseGate;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The release gate's DEFAULT, which this ticket flipped: a deployment that configures nothing now
 * serves the SCIM interface.
 *
 * <p><strong>No property is set here on purpose</strong>, and that is the whole point of the class.
 * Declaring {@code app.scim.enabled=true} would prove only that the flag works when set — which
 * {@code ScimReleaseGateIntegrationTests} already proves for the other value — and would leave the
 * case a real deployment actually hits, an absent setting, untested. The default lives in
 * {@code ScimSecurityConfig}'s {@code @Value} expression rather than in any YAML, so an unset
 * property reaches the gate here exactly as it would in production.
 *
 * <p>Why the default moved: Users and Groups are one release capability. A directory that could
 * create Users but had no Groups could not express authority, so a connector provisioning against it
 * would build something that means less than it will mean later — and the gate existed to keep that
 * half-built surface unreachable. Groups are complete as of this ticket, so the gate stops hiding a
 * finished interface.
 *
 * <p>This is the ticket's sixth acceptance criterion, asserted against the deployed default rather
 * than against a test-resources copy of it: the assertions below read the {@link ScimReleaseGate}
 * bean the application context actually built, and drive real requests through the real filter
 * chain.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimReleaseGateDefaultIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimReleaseGate releaseGate;

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

    @Test
    void the_gate_is_open_when_nothing_is_configured() {
        assertThat(releaseGate.open())
                .as("the default is a fact about the code, not about a configuration file")
                .isTrue();
    }

    /**
     * Discovery is reachable, which is the observable half of an open gate: a closed gate answers
     * {@code 404} ahead of authentication, an open one lets the request reach the bearer chain,
     * which challenges it — discovery needs a valid token like every SCIM path (ADR 0010).
     *
     * <p>Asserted as a real {@code 401} through the real chain rather than by reading the bean
     * twice — the gate filter sits ahead of authentication, so "the flag is true" and "the namespace
     * answers" are two different claims and only the second one is what a connector experiences.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/scim/v2/ServiceProviderConfig",
        "/scim/v2/ResourceTypes",
        "/scim/v2/ResourceTypes/User",
        "/scim/v2/ResourceTypes/Group",
        "/scim/v2/Schemas",
    })
    void discovery_is_reachable_by_default_and_demands_a_token(String path) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus())
                .as("an open gate reaches the bearer chain, which challenges a missing token")
                .isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    /**
     * An open gate is not an open door. The resource endpoints are reachable — so they no longer
     * answer {@code 404} as a closed gate makes them — but they still demand a bearer token, and
     * answer {@code 401} without one.
     *
     * <p>This is the assertion that makes the flip safe to ship: flipping a release gate is only
     * defensible if what it exposes was already authenticated, and a test that checked the gate
     * alone would not have said so.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2/Users", "/scim/v2/Groups"})
    void the_resource_endpoints_are_reachable_but_still_demand_a_token(String path) throws Exception {
        assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                .as("reachable, so not a 404 — but unauthenticated, so not a 200 either")
                .isEqualTo(401);
    }
}
