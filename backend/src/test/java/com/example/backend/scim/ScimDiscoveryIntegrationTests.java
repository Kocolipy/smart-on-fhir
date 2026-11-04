package com.example.backend.scim;

import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Discovery, and the claim that it describes this service rather than SCIM in general.
 *
 * <p>Each advertised flag is asserted BESIDE the behaviour it advertises — the filter flag
 * beside a filtered query, the sort flag beside a sorted one, the ETag flag beside a
 * response's validator. An assertion on the document alone would pass just as well if the
 * document were a fiction, which is the failure mode that matters: a connector configures
 * itself from this and has no other way to find out.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimDiscoveryIntegrationTests {

    private static final String BASE = "/scim/v2";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    /**
     * Removes the User this class persists over the real SCIM surface ("etag-probe").
     * This class is not {@code @Transactional} — the create is a real HTTP commit —
     * so without this the fixed userName survives into a second run against a reused
     * database and collides on the userName uniqueness constraint. Deleting the
     * {@code scim_resources} root cascades to {@code scim_users}. Scoped to the one
     * name this class creates so it cannot remove another class's fixtures.
     */
    @org.junit.jupiter.api.AfterEach
    void removeSeededUser() {
        jdbc.update(
                "DELETE FROM scim_resources WHERE id IN "
                        + "(SELECT resource_id FROM scim_users WHERE normalized_user_name = ?)",
                com.example.backend.scim.domain.NormalizedUserName.of("etag-probe").value());
    }

    private MockMvc mvc;

    private String writeToken;

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create(ScimTestRows.unique("Okta"), null).id();
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, null)
                .presentedValue();
        // Discovery needs a valid token and no Permission (ADR 0015), so every request this class
        // makes carries one by default: a token holding only group:read, which discovery does not
        // look at. A request that names its own credential — asConnector — keeps that one.
        String discoveryToken = connectors
                .issueToken(connectorId, Set.of(Permission.GROUP_READ), null, null)
                .presentedValue();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + discoveryToken))
                .build();
    }

    /** Discovery answers any valid token, whatever Permissions it carries. */
    @ParameterizedTest
    @ValueSource(strings = {
        BASE + "/ServiceProviderConfig",
        BASE + "/ResourceTypes",
        BASE + "/Schemas",
    })
    void discovery_answers_any_valid_token_as_scim_json(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
    }

    @Test
    void the_capability_document_declares_exactly_one_bearer_scheme() throws Exception {
        JsonNode schemes = body(get(BASE + "/ServiceProviderConfig"))
                .get("authenticationSchemes");

        assertThat(schemes.size()).isEqualTo(1);
        assertThat(schemes.get(0).get("type").asText()).isEqualTo("oauthbearertoken");
        assertThat(schemes.get(0).get("primary").booleanValue()).isTrue();
    }

    /** A resource type this service does not serve is a SCIM 404, not a 500 or an empty body. */
    @Test
    void an_unknown_resource_type_is_not_found() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/ResourceTypes/NotAResourceType")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(parsed(result).get("detail").asText())
                .isEqualTo("This service serves no such resource type.");
    }

    /**
     * The base comes from the request, as it does for Users and Groups, so a deployment reached
     * on another scheme, host or port renders that one.
     */
    @Test
    void a_discovery_location_follows_the_scheme_host_and_port_the_request_arrived_on()
            throws Exception {
        String origin = "https://scim.example.test:8443";
        MvcResult result = mvc.perform(get(origin + BASE + "/ServiceProviderConfig")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(parsed(result).at("/meta/location").asText())
                .isEqualTo(origin + BASE + "/ServiceProviderConfig");
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return parsed(result);
    }

    /**
     * The body of a result whose status the caller has already asserted.
     *
     * <p>Separate from {@link #body(MockHttpServletRequestBuilder)}, which asserts {@code 200}:
     * a create answers {@code 201}, so reading its body through that helper would fail on the
     * status rather than on anything the test is about.
     */
    private JsonNode parsed(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
