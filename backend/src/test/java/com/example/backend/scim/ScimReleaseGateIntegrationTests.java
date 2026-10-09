package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The release gate, closed — which a deployment now gets only by asking for it.
 *
 * <p><strong>The property is declared here, and it did not used to be.</strong> While Users and
 * Groups were being built the default was CLOSED, so this class deliberately set nothing: the point
 * was that a deployment which never heard of the flag got no SCIM interface. Groups are complete as
 * of this ticket, so the default is now OPEN and the untouched-configuration case is the opposite
 * one — asserted in {@code ScimReleaseGateDefaultIntegrationTests}, which still sets nothing.
 *
 * <p>What this class tests is therefore no longer a default but a capability: an operator who
 * authenticates by password only and provisions nothing can turn the namespace off, and while it is
 * off every path in it — discovery included — is simply not there.
 */
@SpringBootTest
@TestPropertySource(properties = "app.scim.enabled=false")
@Import(ContainerTestConfiguration.class)
class ScimReleaseGateIntegrationTests {

    private static final String USERS = "/scim/v2/Users";

    private static final String CREATE_BODY = """
            {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"gated"}""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

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

    /**
     * Every path in the namespace, discovery included, is simply not there.
     *
     * <p>Discovery is the interesting one: it needs no Permission when the gate is open, so a
     * closed gate that only covered the resource endpoints would leave the service advertising
     * a SCIM interface it does not serve.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/scim/v2/ServiceProviderConfig",
        "/scim/v2/ResourceTypes",
        "/scim/v2/ResourceTypes/User",
        "/scim/v2/Schemas",
        "/scim/v2/Users",
        "/scim/v2/Users/8a5c1f4e-0000-4000-8000-000000000001",
        "/scim/v2/Groups",
    })
    void the_whole_namespace_answers_not_found(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString())
                .as("a closed gate answers as SCIM, so it cannot be mistaken for the SPA")
                .contains("urn:ietf:params:scim:api:messages:2.0:Error");
    }

    /**
     * And never as the single-page application's HTML shell, which is what a reserved-path
     * mistake would produce — with a {@code 200} a provisioning client would read as an
     * empty success.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2/Users", "/scim/v2/Userz", "/scim/v2/anything/at/all"})
    void a_gated_path_never_falls_through_to_the_single_page_shell(String path)
            throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("<html")
                .doesNotContain("<!doctype");
    }

    /**
     * The gate is ahead of authentication, which this proves by elimination: a garbage token
     * is what the bearer filter answers {@code 401} to, so a {@code 404} here means the gate
     * decided first. Without that ordering the closed namespace could be probed for whether
     * it exists.
     */
    @Test
    void a_closed_gate_answers_before_the_credential_is_looked_at() throws Exception {
        assertThat(mvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        assertThat(mvc.perform(get(USERS)).andReturn().getResponse().getStatus())
                .as("and with no credential at all: 404, not the bearer challenge")
                .isEqualTo(404);
    }

    /** A real write credential does not open it, and nothing is created. */
    @Test
    void a_valid_write_token_creates_nothing_while_the_gate_is_closed() throws Exception {
        UUID connectorId = connectors.create("Okta", "test-admin").id();
        String token = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL)
                .presentedValue();
        long before = users();

        MvcResult result = mvc.perform(post(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.valueOf("application/scim+json"))
                        .content(CREATE_BODY))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(users()).isEqualTo(before);
    }

    /**
     * The closed gate's own response declares itself as SCIM JSON in UTF-8.
     *
     * <p>This filter writes its body before any Spring MVC message converter is reached, so
     * the content type and encoding are its own to set. Without them a provisioning client
     * receives an error document it may decline to parse, and a non-ASCII detail would be
     * decoded with the container's default charset rather than UTF-8.
     *
     * <p>The charset is asserted on the {@code Content-Type} HEADER rather than through
     * {@code getCharacterEncoding()}, and that is the whole point of this assertion.
     * {@code MockHttpServletResponse} defaults its own encoding to UTF-8, so the accessor
     * answers UTF-8 whether or not the filter set anything — mutation testing removed the
     * {@code setCharacterEncoding} call and this test still passed. A real Tomcat defaults to
     * ISO-8859-1, so the production line is load-bearing and the mock was hiding it. The
     * header is built from the charset that was EXPLICITLY set, so it distinguishes the two.
     */
    @Test
    void the_closed_gates_own_response_declares_scim_json_in_utf8() throws Exception {
        MvcResult result = mvc.perform(get(USERS)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
        assertThat(result.getResponse().getHeader("Content-Type"))
                .as("the charset must be set explicitly, not inherited from a container default")
                .containsIgnoringCase("charset=utf-8");
    }

    private long users() {
        return jdbc.queryForObject("SELECT count(*) FROM scim_users", Long.class);
    }
}
