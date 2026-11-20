package com.example.backend.scim;

import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Discovery's answers beyond what it advertises: the detail of a resource type it does not
 * serve, and a location built from the scheme, host and port the request arrived on. What the
 * documents advertise, and that any valid token reads them, is the discovery rows of
 * {@code ScimConformanceCases}'.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimDiscoveryIntegrationTests {

    private static final String BASE = "/scim/v2";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create(ScimTestRows.unique("Okta"), null).id();
        // Discovery needs a valid token and no Permission (ADR 0015), so every request this class
        // makes carries one: a token holding only group:read, which discovery does not look at.
        String discoveryToken = connectors
                .issueToken(connectorId, Set.of(Permission.GROUP_READ), null, null)
                .presentedValue();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + discoveryToken))
                .build();
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

    private JsonNode parsed(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
