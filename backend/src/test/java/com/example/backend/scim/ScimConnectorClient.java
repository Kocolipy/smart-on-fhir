package com.example.backend.scim;

import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A SCIM connector as a test drives it: a fresh connector holding a token with every Permission,
 * and the SCIM API behind the request-id filter and the security chain, as a connector meets it.
 *
 * <p>A plain object built from the context's beans rather than a bean of its own, so a class that
 * uses it adds nothing to its context's configuration and shares the context it would have had.
 */
public final class ScimConnectorClient {

    public static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;

    private final String token;

    private ScimConnectorClient(MockMvc mvc, String token) {
        this.mvc = mvc;
        this.token = token;
    }

    /** Creates a connector named {@code connectorName} and issues it an all-Permission token. */
    public static ScimConnectorClient create(WebApplicationContext context,
            ConnectorAdministrationService connectors, RequestIdFilter requestIdFilter,
            Filter springSecurityFilterChain, String connectorName) {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create(connectorName, null).id();
        String token = connectors.issueToken(connectorId, TokenPermissions.ALL, null, null).presentedValue();
        return new ScimConnectorClient(mvc, token);
    }

    /** Performs {@code request} as the connector, with its bearer token. */
    public MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    /** The {@code id} of the resource a SCIM response body describes. */
    public static UUID id(MvcResult result) throws Exception {
        return UUID.fromString(
                JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
