package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract check itself fails on each kind of drift it claims to catch.
 *
 * <p>The fixture suites pass because the implementation and {@code docs/openapi.yaml} agree; that
 * is only evidence if the check would have said so when they did not. Each case here hands the
 * checker one response that departs from the real document in exactly one way and asserts the
 * departure is reported — and one that conforms, to show the reports are not unconditional.
 */
class OpenApiContractTests {

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String USER_ID = "2b0c5a0e-6a43-4b4a-8f2c-7d3c1a8e9f10";

    private static final String USER_BODY = """
            {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"id":"%s",
             "userName":"ada","active":true,
             "meta":{"resourceType":"User","created":"2026-01-01T00:00:00Z",
                     "lastModified":"2026-01-01T00:00:00Z",
                     "location":"http://localhost/scim/v2/Users/%s","version":"\\"1\\""}}"""
            .formatted(USER_ID, USER_ID);

    private static final String ERROR_BODY = """
            {"schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],"status":"404",
             "detail":"No SCIM endpoint is served at this path."}""";

    @Test
    void the_document_is_read_with_its_operations() {
        assertThat(CONTRACT.operations()).extracting(Object::toString)
                .contains("POST /scim/v2/Users", "GET /api/self", "GET /scim/v2/Me");
    }

    /** {@code sessionCookie: [superuser]} is the Superuser, a requirement of its own — no Permission. */
    @Test
    void a_session_requirement_naming_the_superuser_is_read_as_the_superuser() {
        assertThat(CONTRACT.operations())
                .filteredOn(operation -> operation.toString().equals("POST /api/admin/connectors"))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.requirement().access())
                            .isEqualTo(OpenApiContract.Access.SUPERUSER);
                    assertThat(operation.requirement().permission()).isNull();
                });
    }

    @Test
    void a_session_requirement_naming_a_permission_is_still_read_as_that_permission() {
        assertThat(CONTRACT.operations())
                .filteredOn(operation -> operation.toString().equals("GET /api/admin/connectors"))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.requirement().access())
                            .isEqualTo(OpenApiContract.Access.PERMISSION);
                    assertThat(operation.requirement().permission()).isEqualTo("connector:read");
                });
    }

    @Test
    void a_conforming_response_has_no_violations_and_records_its_coverage() {
        OpenApiContract.Verdict verdict = CONTRACT.check("POST", "/scim/v2/Users",
                response(201, "application/scim+json", USER_BODY, "Location", "ETag"));
        assertThat(verdict.violations()).isEmpty();
        assertThat(verdict.covered()).contains("POST /scim/v2/Users 201");
    }

    @Test
    void an_undocumented_status_is_reported() {
        OpenApiContract.Verdict verdict = CONTRACT.check("GET", "/api/self",
                response(418, null, "", new String[0]));
        assertThat(verdict.violations()).singleElement().asString()
                .contains("status not documented for GET /api/self");
        assertThat(verdict.covered()).isEmpty();
    }

    @Test
    void a_missing_documented_header_is_reported() {
        assertThat(CONTRACT.check("POST", "/scim/v2/Users",
                response(201, "application/scim+json", USER_BODY, "ETag")).violations())
                .singleElement().asString().contains("documented header Location is absent");
    }

    /**
     * RFC 7643 §3.1: a relative {@code meta.location} is not the URI of the resource. Asserted on
     * the path the violation names rather than the checker's wording, so rephrasing the message
     * does not break the test.
     */
    @Test
    void a_relative_meta_location_is_reported() {
        String relative = USER_BODY.replace("http://localhost/scim/v2/Users/", "/scim/v2/Users/");
        assertThat(CONTRACT.check("GET", "/scim/v2/Users/" + USER_ID,
                response(200, "application/scim+json", relative, "Location", "ETag")).violations())
                .singleElement().asString().contains("$.meta.location: ");
    }

    /** The same holds inside a list: a listed resource's location is checked, not just a by-id one. */
    @Test
    void a_relative_meta_location_in_a_list_is_reported() {
        String list = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                 "totalResults":1,"startIndex":1,"itemsPerPage":1,
                 "Resources":[{"id":"User",
                   "meta":{"resourceType":"ResourceType","location":"/scim/v2/ResourceTypes/User"}}]}""";
        assertThat(CONTRACT.check("GET", "/scim/v2/ResourceTypes",
                response(200, "application/scim+json", list)).violations())
                .singleElement().asString().contains("$.Resources[0].meta.location: ");
    }

    @Test
    void a_returned_write_only_property_is_reported() {
        String leaked = USER_BODY.replace("\"active\":true", "\"active\":true,\"password\":\"x\"");
        assertThat(CONTRACT.check("GET", "/scim/v2/Users/" + USER_ID,
                response(200, "application/scim+json", leaked, "Location", "ETag")).violations())
                .singleElement().asString().contains("$.password: a writeOnly property");
    }

    @Test
    void a_body_on_a_bodiless_response_is_reported() {
        assertThat(CONTRACT.check("DELETE", "/scim/v2/Users/" + USER_ID,
                response(204, "application/scim+json", "{}")).violations())
                .singleElement().asString().contains("documented with no body");
    }

    @Test
    void an_undocumented_media_type_is_reported() {
        assertThat(CONTRACT.check("POST", "/scim/v2/Users",
                response(201, "text/html", USER_BODY, "Location", "ETag")).violations())
                .singleElement().asString().contains("Content-Type text/html")
                .contains("is not one of [application/scim+json, application/json]");
    }

    @Test
    void a_schema_departure_is_reported() {
        String numericStatus = ERROR_BODY.replace("\"404\"", "404");
        assertThat(CONTRACT.check("GET", "/scim/v2/Users/" + USER_ID,
                response(404, "application/scim+json", numericStatus)).violations())
                .singleElement().asString().contains("$.status: expected type");
    }

    @Test
    void an_undocumented_scim_type_is_reported() {
        String invented = ERROR_BODY.replace("\"detail\"", "\"scimType\":\"tooLate\",\"detail\"");
        assertThat(CONTRACT.check("GET", "/scim/v2/Users/" + USER_ID,
                response(404, "application/scim+json", invented)).violations())
                .singleElement().asString().contains("\"tooLate\" is not one of");
    }

    @Test
    void a_refusal_with_no_operation_is_held_against_the_namespace_response() {
        OpenApiContract.Verdict served = CONTRACT.check("GET", "/scim/v2/Devices",
                response(404, "application/scim+json", ERROR_BODY));
        assertThat(served.violations()).isEmpty();
        assertThat(served.covered()).contains("ANY /scim/v2/** 404");

        assertThat(CONTRACT.check("GET", "/scim/v2/Devices",
                response(404, "text/html", "<html/>")).violations()).isNotEmpty();
        assertThat(CONTRACT.check("GET", "/api/nothing-here",
                response(404, null, "")).violations()).singleElement().asString()
                .contains("no documented operation and no namespace response");
    }

    @Test
    void the_most_literal_template_wins() {
        assertThat(CONTRACT.template("/scim/v2/Users/.search")).contains("/scim/v2/Users/.search");
        assertThat(CONTRACT.template("/scim/v2/Users/" + USER_ID)).contains("/scim/v2/Users/{id}");
        assertThat(CONTRACT.template("/scim/v2/Users/" + USER_ID + "/x")).isEmpty();
    }

    @Test
    void the_validator_enforces_each_keyword_it_claims() throws Exception {
        JsonNode schema = JSON.readTree("""
                {"type":"object","required":["id"],"additionalProperties":false,
                 "properties":{"id":{"type":"string","format":"uuid"},
                               "n":{"type":"integer","minimum":1,"maximum":3},
                               "s":{"type":"string","minLength":2,"maxLength":3,"pattern":"^a"},
                               "e":{"type":"string","enum":["x"]},
                               "t":{"type":"string","format":"date-time"},
                               "a":{"type":"array","minItems":1,"maxItems":1,
                                    "items":{"type":["string","null"]}}}}""");
        assertThat(CONTRACT.validate(schema, JSON.readTree("""
                {"id":"%s","n":2,"s":"ab","e":"x","t":"2026-01-01T00:00:00Z","a":[null]}"""
                .formatted(USER_ID)), "$")).isEmpty();
        assertThat(CONTRACT.validate(schema, JSON.readTree("""
                {"id":"not-a-uuid","n":9,"s":"b","e":"y","t":"yesterday","a":[1,2],"z":0}"""),
                "$")).containsExactlyInAnyOrder(
                        "$.id: not a valid uuid",
                        "$.n: above maximum 3",
                        "$.s: shorter than minLength 2",
                        "$.s: does not match pattern \"^a\"",
                        "$.e: \"y\" is not one of [\"x\"]",
                        "$.t: not a valid date-time",
                        "$.a: more items than maxItems 1",
                        "$.a[0]: expected type [\"string\",\"null\"] but was NUMBER",
                        "$.a[1]: expected type [\"string\",\"null\"] but was NUMBER",
                        "$.z: not a declared property, and additionalProperties is false");
        assertThat(CONTRACT.validate(schema, JSON.readTree("{}"), "$"))
                .containsExactly("$: required property id is absent");
    }

    /**
     * A string's length is its code points, as JSON Schema defines it and as the SCIM columns
     * count: three supplementary characters are six UTF-16 units and meet a maxLength of 3.
     */
    @Test
    void string_lengths_are_counted_in_code_points() throws Exception {
        JsonNode schema = JSON.readTree("""
                {"type":"string","minLength":3,"maxLength":3}""");
        String three = "\uD83D\uDE00".repeat(3);

        assertThat(CONTRACT.validate(schema, JSON.valueToTree(three), "$")).isEmpty();
        assertThat(CONTRACT.validate(schema, JSON.valueToTree(three + "x"), "$"))
                .containsExactly("$: longer than maxLength 3");
        assertThat(CONTRACT.validate(schema, JSON.valueToTree("\uD83D\uDE00"), "$"))
                .containsExactly("$: shorter than minLength 3");
    }

    @Test
    void one_of_requires_exactly_one_matching_branch() throws Exception {
        JsonNode schema = JSON.readTree("""
                {"oneOf":[{"type":"object","required":["a"]},
                          {"type":"object","required":["b"]}]}""");

        assertThat(CONTRACT.validate(schema, JSON.readTree("{\"a\":1}"), "$")).isEmpty();
        assertThat(CONTRACT.validate(schema, JSON.readTree("{\"b\":1}"), "$")).isEmpty();
        assertThat(CONTRACT.validate(schema, JSON.readTree("{}"), "$"))
                .containsExactly("$: matches 0 oneOf branches, not exactly 1");
        assertThat(CONTRACT.validate(schema, JSON.readTree("{\"a\":1,\"b\":1}"), "$"))
                .containsExactly("$: matches 2 oneOf branches, not exactly 1");
    }

    @Test
    void the_validator_covers_every_keyword_the_document_uses() {
        assertThat(CONTRACT.unsupportedKeywords()).isEmpty();
    }

    private static MockHttpServletResponse response(int status, String contentType, String body,
            String... headers) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        if (contentType != null) {
            response.setContentType(contentType);
        }
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        for (String header : headers) {
            response.setHeader(header, "present");
        }
        try {
            response.getWriter().write(body);
            response.getWriter().flush();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return response;
    }
}
