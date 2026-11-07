package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.controller.ScimPatchPathGrammar.Comparison;
import com.example.backend.scim.controller.ScimPatchPathGrammar.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * A PATCH path's text in, a parsed path or a value filter's comparisons out — or the exact refusal
 * the response carries. One grammar for every resource: the only resource-specific input is the
 * core schema URN a path may be prefixed with. No PatchOp body is needed: each case is the text of
 * a path, or the text between its brackets.
 */
class ScimPatchPathGrammarTests {

    private static final String USER_URN = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_URN = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String ONLY_EQ_AND =
            "Only 'sub-attribute eq value' comparisons joined by 'and' are supported in a value"
                    + " filter.";

    private static final String NOT_A_LITERAL =
            "A compared value must be a JSON string, boolean or null.";

    private static final String GROUPING = "Grouping is not supported in a value filter.";

    private static final String UNTERMINATED = "The value filter has an unterminated string.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Comparison comparison(String subAttribute, String literal) {
        return new Comparison(subAttribute, JSON.readTree(literal));
    }

    private static void refusedAs(String filter, String scimType, String detail) {
        assertThatThrownBy(() -> ScimPatchPathGrammar.valueFilter(filter))
                .as(filter)
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.detail()).isEqualTo(detail);
                });
    }

    // ---- paths ----------------------------------------------------------------------------

    @Test
    void a_path_reads_its_attribute_filter_and_sub_attribute() {
        assertThat(ScimPatchPathGrammar.path("displayName", USER_URN))
                .isEqualTo(new Path("displayname", null, null));
        assertThat(ScimPatchPathGrammar.path("Name.GivenName", USER_URN))
                .isEqualTo(new Path("name", null, "givenname"));
        assertThat(ScimPatchPathGrammar.path("Emails[Type eq \"Work\"]", USER_URN))
                .isEqualTo(new Path("emails", "Type eq \"Work\"", null));
        assertThat(ScimPatchPathGrammar.path("emails[type eq \"work\"].Value", USER_URN))
                .isEqualTo(new Path("emails", "type eq \"work\"", "value"));
    }

    /** RFC 7644 §3.10: the resource's core schema URN may prefix a path, in any case. */
    @Test
    void a_path_is_read_without_the_resources_schema_prefix_in_any_case() {
        assertThat(ScimPatchPathGrammar.path(USER_URN + ":name.familyName", USER_URN))
                .isEqualTo(new Path("name", null, "familyname"));
        assertThat(ScimPatchPathGrammar.path(
                "URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER:userName", USER_URN))
                .isEqualTo(new Path("username", null, null));
        assertThat(ScimPatchPathGrammar.path(
                "urn:ietf:params:scim:schemas:core:2.0:group:displayName", GROUP_URN))
                .isEqualTo(new Path("displayname", null, null));
    }

    /** {@code attrPath "[" valFilter "]"}: the prefix belongs to the attrPath of a value path too. */
    @Test
    void a_value_path_may_carry_the_schema_prefix() {
        assertThat(ScimPatchPathGrammar.path(GROUP_URN + ":members[value eq \"m-1\"]", GROUP_URN))
                .isEqualTo(new Path("members", "value eq \"m-1\"", null));
        assertThat(ScimPatchPathGrammar.path(USER_URN + ":emails[type eq \"work\"].value",
                USER_URN))
                .isEqualTo(new Path("emails", "type eq \"work\"", "value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "emails..value", "emails[", "emails[]", "1userName",
            "name.givenName.x", "userName!", "emails[type eq \"work\"]x",
            "urn:ietf:params:scim:schemas:core:2.0:User:",
            "urn:ietf:params:scim:schemas:core:2.0:Group:displayName",
            " locale", "locale ", "  locale \t", "\tlocale",
            "urn:ietf:params:scim:schemas:core:2.0:User: locale"})
    void text_that_is_not_an_attribute_path_is_invalid_path(String text) {
        assertThatThrownBy(() -> ScimPatchPathGrammar.path(text, USER_URN))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo("invalidPath");
                    assertThat(refusal.detail())
                            .isEqualTo("Not a valid attribute path: " + ScimEcho.sanitized(text));
                });
    }

    @Test
    void only_a_bare_name_is_an_attribute_name() {
        assertThat(ScimPatchPathGrammar.isAttributeName("displayName")).isTrue();
        assertThat(ScimPatchPathGrammar.isAttributeName("x_$-9")).isTrue();
        assertThat(ScimPatchPathGrammar.isAttributeName("name.givenName")).isFalse();
        assertThat(ScimPatchPathGrammar.isAttributeName("emails[type eq \"w\"]")).isFalse();
        assertThat(ScimPatchPathGrammar.isAttributeName("1locale")).isFalse();
        assertThat(ScimPatchPathGrammar.isAttributeName("")).isFalse();
    }

    // ---- value filters: what is read ------------------------------------------------------

    @Test
    void one_comparison_reads_as_its_sub_attribute_and_its_json_literal() {
        assertThat(ScimPatchPathGrammar.valueFilter("type eq \"work\""))
                .containsExactly(comparison("type", "\"work\""));
        assertThat(ScimPatchPathGrammar.valueFilter("primary eq true"))
                .containsExactly(comparison("primary", "true"));
        assertThat(ScimPatchPathGrammar.valueFilter("type eq null"))
                .containsExactly(comparison("type", "null"));
        assertThat(ScimPatchPathGrammar.valueFilter("value eq 7"))
                .containsExactly(comparison("value", "7"));
    }

    /** Sub-attribute and operator are case-insensitive; the compared value is kept as written. */
    @Test
    void names_and_operator_are_case_insensitive_and_surrounding_whitespace_is_ignored() {
        assertThat(ScimPatchPathGrammar.valueFilter("  TYPE   EQ   \"Work\"  "))
                .containsExactly(comparison("type", "\"Work\""));
    }

    @Test
    void and_joins_comparisons_in_order_whatever_its_case_or_whitespace() {
        assertThat(ScimPatchPathGrammar.valueFilter(
                "value eq \"a@b.example\" AND primary eq true\tand\ttype eq null"))
                .isEqualTo(List.of(
                        comparison("value", "\"a@b.example\""),
                        comparison("primary", "true"),
                        comparison("type", "null")));
    }

    @Test
    void and_inside_a_quoted_value_is_part_of_the_value() {
        assertThat(ScimPatchPathGrammar.valueFilter("value eq \"a and b@x.example\""))
                .containsExactly(comparison("value", "\"a and b@x.example\""));
    }

    /** An escaped quote does not end the string, so an {@code and} after it is still inside. */
    @Test
    void an_escaped_quote_does_not_end_the_quoted_value() {
        assertThat(ScimPatchPathGrammar.valueFilter("value eq \"quote\\\" and x\""))
                .containsExactly(comparison("value", "\"quote\\\" and x\""));
        assertThat(ScimPatchPathGrammar.valueFilter("value eq \"back\\\\\" and type eq \"w\""))
                .containsExactly(
                        comparison("value", "\"back\\\\\""),
                        comparison("type", "\"w\""));
    }

    /** Grouping characters inside a quoted value are part of the value, not grouping. */
    @Test
    void brackets_inside_a_quoted_value_are_not_grouping() {
        assertThat(ScimPatchPathGrammar.valueFilter("type eq \"(w[o]rk)\""))
                .containsExactly(comparison("type", "\"(w[o]rk)\""));
    }

    // ---- value filters: what is refused ---------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "type", "type eq",
            "type eq \"work\" and and type eq \"home\"", "type.sub eq \"x\""})
    void empty_or_incomplete_input_is_not_a_comparison(String filter) {
        refusedAs(filter, "invalidFilter", ONLY_EQ_AND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type co \"wo\"", "type ne \"work\"", "type sw \"w\"",
            "type gt \"w\"", "primary is true"})
    void any_operator_but_eq_is_unsupported(String filter) {
        refusedAs(filter, "invalidFilter", "Only the eq operator is supported in a value filter.");
    }

    @Test
    void a_unary_operator_is_unsupported() {
        refusedAs("type pr", "invalidFilter", ONLY_EQ_AND);
    }

    /**
     * {@code or} is not split on, and neither is an {@code and} with no comparison after it, so
     * the trailing text joins the value, which is then not one literal.
     */
    @ParameterizedTest
    @ValueSource(strings = {"type eq \"work\" or type eq \"home\"", "type eq work",
            "type eq \"work\" and", "type eq \"work\" xyz value eq \"x\"",
            "primary eq truexand value eq \"x\"", "type eq \"work\" andvalue eq \"x\""})
    void text_that_is_not_one_json_literal_is_refused(String filter) {
        refusedAs(filter, "invalidFilter", NOT_A_LITERAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"(type eq \"work\")", "type eq \"work\")", "[type eq \"work\"]",
            "(type eq \"work\"", "not (type eq \"work\")", "type eq \"work\" and [value eq \"x\""})
    void grouping_is_refused_as_grouping(String filter) {
        refusedAs(filter, "invalidFilter", GROUPING);
    }

    /** An unterminated string is refused as a malformed path, as it always has been. */
    @ParameterizedTest
    @ValueSource(strings = {"type eq \"work", "value eq \"abc\\", "value eq \"a\\\""})
    void an_unterminated_string_is_invalid_path(String filter) {
        refusedAs(filter, "invalidPath", UNTERMINATED);
    }
}
