package com.example.backend;

import com.example.backend.scim.ScimConditionalWrites;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A SCIM request as a MockMvc test sends it: the SCIM media type, and the connector's bearer
 * token.
 */
public final class ScimHttp {

    /** The media type a SCIM body is sent and answered in, as a header value. */
    public static final String SCIM_JSON_VALUE = "application/scim+json";

    /** The media type a SCIM body is sent and answered in. */
    public static final MediaType SCIM_JSON = MediaType.valueOf(SCIM_JSON_VALUE);

    private ScimHttp() {
    }

    /** {@code request} as the connector holding {@code token}, with nothing else added. */
    public static MockHttpServletRequestBuilder asConnector(
            MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /**
     * {@code request} as a conforming connector holding {@code token} sends it: a PUT, PATCH or
     * DELETE also carries the version it read, in {@code If-Match}
     * ({@link ScimConditionalWrites#currentVersion}). The precondition's own behaviour is pinned
     * in {@code ScimConditionalWriteIntegrationTests}, which sets the header itself.
     */
    public static MockHttpServletRequestBuilder asConnector(
            MockHttpServletRequestBuilder request, String token, JdbcTemplate jdbc) {
        return asConnector(request, token).with(ScimConditionalWrites.currentVersion(jdbc));
    }
}
