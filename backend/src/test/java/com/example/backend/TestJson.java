package com.example.backend;

import java.util.UUID;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper the tests read and write bodies with, and the reads every MockMvc test
 * makes of a response: its body as a tree, and the {@code id} a created resource answers with.
 *
 * <p>A plain mapper, configured as nothing in the application is: a test reads what the service
 * wrote, so it must not share the service's own customisations and agree with them by
 * construction.
 */
public final class TestJson {

    /** The shared mapper. Thread-safe once built, so one instance serves every test. */
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private TestJson() {
    }

    /** The response body of {@code result}, parsed. */
    public static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** The {@code id} of the resource the response body describes. */
    public static UUID id(MvcResult result) throws Exception {
        return UUID.fromString(body(result).get("id").asText());
    }
}
