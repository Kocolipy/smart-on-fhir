package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.InvalidScimFilterException;
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A query read from query parameters and from a {@code SearchRequest} body. */
class ScimQueryRequestTests {

    private static final Set<ScimResourceType> USERS = Set.of(ScimResourceType.USER);

    private static final String SCHEMA = "[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"]";

    private final JsonMapper json = JsonMapper.builder().build();

    private ScimQueryRequest search(String members) {
        JsonNode body = json.readTree("{\"schemas\":" + SCHEMA + (members.isEmpty() ? "" : ",")
                + members + "}");
        return ScimQueryRequest.fromSearchRequest(USERS, body);
    }

    @Test
    void every_search_request_member_is_read_and_names_match_case_insensitively() {
        ScimQueryRequest request = search("""
                "Filter":"userName pr","SORTBY":"userName","sortOrder":"descending",
                "startIndex":3,"COUNT":7,"attributes":["userName","emails.value"]""");

        assertThat(request.query().filter()).isInstanceOf(ScimFilter.Presence.class);
        assertThat(request.query().sort().descending()).isTrue();
        assertThat(request.query().page()).isEqualTo(new PageRequest(3, 7));
        assertThat(request.attributes()).isEqualTo("userName,emails.value");
        assertThat(request.excludedAttributes()).isNull();
    }

    @Test
    void absent_and_null_members_are_the_defaults() {
        ScimQueryRequest request = search("\"filter\":null,\"count\":null,\"excludedAttributes\":null");

        assertThat(request.query().filter()).isNull();
        assertThat(request.query().sort()).isNull();
        assertThat(request.query().page()).isEqualTo(PageRequest.FIRST_PAGE);
        assertThat(request.attributes()).isNull();
        assertThat(request.excludedAttributes()).isNull();
        assertThat(search("").query().page()).isEqualTo(PageRequest.FIRST_PAGE);
    }

    @Test
    void an_attribute_list_may_also_be_one_comma_separated_string() {
        assertThat(search("\"excludedAttributes\":\"meta,emails\"").excludedAttributes())
                .isEqualTo("meta,emails");
    }

    /** Paging values of any size are coerced, not refused: a huge count asks for the maximum. */
    @Test
    void paging_values_beyond_the_integer_range_are_coerced() {
        assertThat(search("\"count\":99999999999999999999,\"startIndex\":-99999999999999999999")
                        .query().page())
                .isEqualTo(new PageRequest(1, PageRequest.MAX_COUNT));
        assertThat(search("\"startIndex\":99999999999999999999").query().page().startIndex())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"filter\":\"userName pr\"}",
            "{\"schemas\":\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"}",
            "{\"schemas\":[]}",
            "{\"schemas\":[5]}",
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\","
                    + "\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"]}",
            "{\"schemas\":" + SCHEMA + ",\"filtr\":\"userName pr\"}",
            "{\"schemas\":" + SCHEMA + ",\"count\":1,\"Count\":2}",
            "[]",
            "\"search\""})
    void a_body_that_is_not_a_search_request_is_invalid_syntax(String body) {
        assertThatThrownBy(() -> ScimQueryRequest.fromSearchRequest(USERS, json.readTree(body)))
                .isInstanceOfSatisfying(ScimErrorException.class,
                        refusal -> assertThat(refusal.scimType()).isEqualTo("invalidSyntax"));
    }

    /**
     * Each malformed body is refused by the rule it breaks, not by a later check it happens to
     * reach: an array falls through to "no schema" if the object check is skipped, and a
     * schemas member that is an object would be indexed as though it were an array.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"]}",
            "{\"schemas\":{\"0\":\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"}}",
            "{\"schemas\":null}"})
    void a_body_declaring_another_schema_is_refused_for_its_schema(String body) {
        assertThatThrownBy(() -> ScimQueryRequest.fromSearchRequest(USERS, json.readTree(body)))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> assertThat(refusal)
                        .hasMessage("A search request declares exactly the schema "
                                + "urn:ietf:params:scim:api:messages:2.0:SearchRequest."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"search\""})
    void a_body_that_is_not_an_object_is_refused_as_not_an_object(String body) {
        assertThatThrownBy(() -> ScimQueryRequest.fromSearchRequest(USERS, json.readTree(body)))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> assertThat(refusal)
                        .hasMessage("A search request body is a JSON object."));
    }

    @Test
    void a_missing_body_is_invalid_syntax() {
        assertThatThrownBy(() -> ScimQueryRequest.fromSearchRequest(USERS, null))
                .isInstanceOf(ScimErrorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"count\":\"10\"", "\"count\":1.5", "\"startIndex\":true", "\"filter\":5",
            "\"sortBy\":[\"userName\"]", "\"sortOrder\":{}", "\"attributes\":5",
            "\"attributes\":[\"userName\",5]", "\"excludedAttributes\":{\"a\":1}"})
    void a_member_of_the_wrong_type_is_an_invalid_value(String member) {
        assertThatThrownBy(() -> search(member)).isInstanceOf(InvalidScimQueryException.class);
    }

    @Test
    void the_search_body_filter_is_validated_like_a_parameter() {
        assertThatThrownBy(() -> search("\"filter\":\"password pr\""))
                .isInstanceOf(InvalidScimFilterException.class);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "-, -, 1, 100", "'', '', 1, 100", "' ', ' ', 1, 100",
            "' 4 ', ' 9 ', 4, 9", "+2, 0, 2, 0",
            "0, -3, 1, 0", "99999999999, 99999999999, 2147483647, 200",
            "-99999999999, 5, 1, 5"})
    void query_parameters_are_parsed_and_coerced(
            String startIndex, String count, int expectedStart, int expectedCount) {
        ScimQueryRequest request = ScimQueryRequest.fromParameters(
                USERS, null, null, null, startIndex, count, "userName", null);

        assertThat(request.query().page()).isEqualTo(new PageRequest(expectedStart, expectedCount));
        assertThat(request.attributes()).isEqualTo("userName");
    }

    @ParameterizedTest
    @CsvSource({"abc,startIndex", "1.5,startIndex", "-,count", "1e3,count"})
    void a_paging_parameter_that_is_not_an_integer_names_itself(String value, String parameter) {
        assertThatThrownBy(() -> ScimQueryRequest.fromParameters(USERS, null, null, null,
                        parameter.equals("startIndex") ? value : null,
                        parameter.equals("count") ? value : null, null, null))
                .isInstanceOf(InvalidScimQueryException.class)
                .hasMessage(parameter + " must be an integer.");
    }
}
