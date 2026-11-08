package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The envelope every SCIM User and Group write body shares, owned here across its full table:
 * the object and schema checks, case-insensitive names read back by their canonical spelling,
 * a name given twice by case, unsupported names, and the typing of a read value.
 *
 * <p>Driven with the real User and Group schemas, the only attribute facts the module reads. The
 * readers' own tests keep one wiring case per entry point and leave these rules here.
 */
class ScimResourceBodyTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ScimResourceSchema USER = ScimResourceSchema.of(ScimResourceType.USER);

    private static final ScimResourceSchema GROUP = ScimResourceSchema.of(ScimResourceType.GROUP);

    private static final ScimAttribute NAME = USER.find("name").orElseThrow();

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String TWICE = " is given more than once, in names that differ only by case.";

    /** A User body declaring the User schema, carrying these further attributes. */
    private static ScimResourceBody user(String attributes) {
        return ScimResourceBody.resource(userJson(attributes), USER);
    }

    private static JsonNode userJson(String attributes) {
        return JSON.readTree("{\"schemas\":[\"" + USER_SCHEMA + "\"]"
                + (attributes.isEmpty() ? "" : "," + attributes) + "}");
    }

    private static void refused(ThrowingCallable read, String scimType, String detail) {
        assertThatThrownBy(read)
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(400);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.detail()).isEqualTo(detail);
                });
    }

    // --- the envelope

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"a body\"", "42", "null"})
    void a_body_that_is_not_an_object_is_not_a_resource(String body) {
        refused(() -> ScimResourceBody.resource(JSON.readTree(body), USER),
                "invalidSyntax", "The request body must be a SCIM resource.");
    }

    @Test
    void an_absent_body_is_not_a_resource() {
        refused(() -> ScimResourceBody.resource(null, GROUP),
                "invalidSyntax", "The request body must be a SCIM resource.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"schemas\":null", ",\"schemas\":\"" + USER_SCHEMA + "\"",
        ",\"schemas\":[]"})
    void a_schemas_declaration_that_is_not_a_non_empty_array_is_invalid_syntax(String schemas) {
        refused(() -> ScimResourceBody.resource(
                        JSON.readTree("{\"userName\":\"bjensen\"" + schemas + "}"), USER),
                "invalidSyntax", "A resource must declare its schemas as a non-empty array.");
    }

    /** Exactly the resource's own core schema, named from the schema the body is read against. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            USER  | ["urn:ietf:params:scim:schemas:core:2.0:Group"]                       | This service implements one User schema only: urn:ietf:params:scim:schemas:core:2.0:User
            USER  | ["urn:ietf:params:scim:schemas:core:2.0:User","urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"] | This service implements one User schema only: urn:ietf:params:scim:schemas:core:2.0:User
            USER  | ["URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER"]                        | This service implements one User schema only: urn:ietf:params:scim:schemas:core:2.0:User
            GROUP | ["urn:ietf:params:scim:schemas:core:2.0:User"]                        | This service implements one Group schema only: urn:ietf:params:scim:schemas:core:2.0:Group
            """)
    void a_declaration_of_any_schema_but_the_resource_own_alone_is_invalid_value(
            ScimResourceType type, String schemas, String detail) {
        refused(() -> ScimResourceBody.resource(
                        JSON.readTree("{\"schemas\":" + schemas + "}"), ScimResourceSchema.of(type)),
                "invalidValue", detail);
    }

    @Test
    void a_body_declaring_its_own_schema_alone_is_read() {
        assertThat(ScimResourceBody.resource(JSON.readTree(
                        "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                                + "\"displayName\":\"Eng\"}"), GROUP)
                .optionalString("displayName"))
                .isEqualTo("Eng");
    }

    /** Read-only attributes are accepted, so a client may write back what it read. */
    @Test
    void a_read_only_attribute_is_accepted() {
        assertThat(user("\"id\":\"x\",\"META\":{},\"groups\":[],\"userName\":\"bjensen\"")
                .requiredString("userName"))
                .isEqualTo("bjensen");
    }

    @Test
    void an_attribute_the_resource_does_not_have_is_invalid_value() {
        refused(() -> user("\"userName\":\"bjensen\",\"title\":\"x\""),
                "invalidValue", "This service does not implement the User attribute: title");
    }

    /** Named as the body gives it, through the echo sanitizer, which lower-cases it. */
    @Test
    void of_several_unsupported_attributes_the_first_in_the_body_is_named() {
        refused(() -> user("\"NickName\":\"y\",\"userName\":\"bjensen\",\"title\":\"x\""),
                "invalidValue", "This service does not implement the User attribute: nickname");
    }

    @Test
    void an_unsupported_group_attribute_is_named_as_a_group_attribute() {
        refused(() -> ScimResourceBody.resource(JSON.readTree(
                        "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                                + "\"displayName\":\"Eng\",\"owner\":\"x\"}"), GROUP),
                "invalidValue", "This service does not implement the Group attribute: owner");
    }

    // --- names match regardless of case, and are read back by their canonical name

    @ParameterizedTest
    @ValueSource(strings = {"userName", "USERNAME", "username", "UserName"})
    void a_name_in_any_case_is_read_by_its_canonical_name(String spelling) {
        assertThat(user("\"" + spelling + "\":\"bjensen\"").requiredString("userName"))
                .isEqualTo("bjensen");
    }

    /** The common attributes, schemas among them, match regardless of case too. */
    @Test
    void the_common_attributes_match_in_any_case() {
        ScimResourceBody body = ScimResourceBody.resource(JSON.readTree(
                "{\"SCHEMAS\":[\"" + USER_SCHEMA + "\"],\"ExternalID\":\"e1\"}"), USER);
        assertThat(body.optionalString("externalId")).isEqualTo("e1");
    }

    @Test
    void a_value_is_not_found_under_a_spelling_other_than_its_canonical_name() {
        assertThat(user("\"userName\":\"bjensen\"").value("USERNAME")).isNull();
    }

    @Test
    void an_absent_attribute_has_no_value() {
        assertThat(user("\"userName\":\"bjensen\"").value("displayName")).isNull();
    }

    // --- a name given twice in spellings that differ only by case

    /**
     * Refused whatever order the spellings come in; named canonically, and when several are
     * duplicated, by the first in the schema's declaration order (common attributes first), so
     * property order changes neither the refusal nor its message.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', textBlock = """
            "userName":"a","USERNAME":"b"                                                  | userName
            "USERNAME":"b","userName":"a"                                                  | userName
            "UserName":"a","username":"b","USERNAME":"c"                                   | userName
            "userName":"a","displayName":"x","emails":[],"DisplayName":"y","EMAILS":[]     | displayName
            "userName":"a","EMAILS":[],"DisplayName":"y","emails":[],"displayName":"x"     | displayName
            "USERNAME":"b","externalId":"e","userName":"a","EXTERNALID":"f"                | externalId
            "userName":"a","Schemas":[]                                                    | schemas
            """)
    void a_name_given_twice_by_case_is_invalid_syntax(String attributes, String attribute) {
        refused(() -> user(attributes), "invalidSyntax", "The attribute " + attribute + TWICE);
    }

    /** The duplicate is refused before an unsupported attribute is looked at. */
    @Test
    void a_duplicate_is_refused_before_an_unsupported_attribute() {
        refused(() -> user("\"title\":\"x\",\"userName\":\"a\",\"USERNAME\":\"b\""),
                "invalidSyntax", "The attribute userName" + TWICE);
    }

    /** ...and before the schemas declaration is looked at. */
    @Test
    void a_duplicate_is_refused_before_the_schema_declaration() {
        refused(() -> ScimResourceBody.resource(
                        JSON.readTree("{\"userName\":\"a\",\"USERNAME\":\"b\"}"), USER),
                "invalidSyntax", "The attribute userName" + TWICE);
    }

    @Test
    void a_group_attribute_given_twice_by_case_is_invalid_syntax() {
        refused(() -> ScimResourceBody.resource(JSON.readTree(
                        "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                                + "\"MEMBERS\":[],\"members\":[]}"), GROUP),
                "invalidSyntax", "The attribute members" + TWICE);
    }

    // --- a complex value, read against its attribute's sub-attributes

    @Test
    void a_sub_attribute_in_any_case_is_read_by_its_canonical_name() {
        assertThat(ScimResourceBody.complex(JSON.readTree("{\"GIVENNAME\":\"Ada\"}"), NAME)
                .optionalString("givenName"))
                .isEqualTo("Ada");
    }

    /** A complex value's read-only sub-attributes are accepted, as a resource's are. */
    @Test
    void a_read_only_sub_attribute_is_accepted() {
        ScimAttribute members = GROUP.find("members").orElseThrow();
        assertThat(ScimResourceBody.complex(JSON.readTree(
                        "{\"Value\":\"m\",\"DISPLAY\":\"Ada\",\"$Ref\":\"x\",\"type\":\"User\"}"),
                        members)
                .requiredString("value"))
                .isEqualTo("m");
    }

    @Test
    void a_sub_attribute_the_attribute_does_not_have_is_invalid_value() {
        refused(() -> ScimResourceBody.complex(JSON.readTree("{\"NickName\":\"x\"}"), NAME),
                "invalidValue", "This service does not implement the name sub-attribute: nickname");
    }

    /** Named by its canonical path, the attribute first, whatever order the spellings come in. */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"givenName\":\"x\",\"GIVENNAME\":\"y\"}",
        "{\"GIVENNAME\":\"y\",\"givenName\":\"x\"}",
        "{\"nickName\":\"z\",\"GivenName\":\"y\",\"givenName\":\"x\"}"})
    void a_sub_attribute_given_twice_by_case_is_invalid_syntax(String value) {
        refused(() -> ScimResourceBody.complex(JSON.readTree(value), NAME),
                "invalidSyntax", "The attribute name.givenName" + TWICE);
    }

    // --- the typing of a read value

    @Test
    void a_required_string_is_the_string_given() {
        assertThat(user("\"userName\":\"bjensen\"").requiredString("userName"))
                .isEqualTo("bjensen");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"userName\":null"})
    void a_required_string_absent_or_null_is_missing(String attributes) {
        refused(() -> user(attributes).requiredString("userName"),
                "invalidSyntax", "A required attribute is missing: userName");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', textBlock = """
            42      | userName must be a string.
            true    | userName must be a string.
            ["a"]   | userName must be a string.
            {}      | userName must be a string.
            ""      | userName must not be blank.
            "   "   | userName must not be blank.
            """)
    void a_required_string_of_another_type_or_blank_is_invalid_value(
            String value, String detail) {
        refused(() -> user("\"userName\":" + value).requiredString("userName"),
                "invalidValue", detail);
    }

    @Test
    void an_optional_string_is_the_string_given() {
        assertThat(user("\"displayName\":\"Babs\"").optionalString("displayName"))
                .isEqualTo("Babs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"displayName\":null"})
    void an_optional_string_absent_or_null_is_null(String attributes) {
        assertThat(user(attributes).optionalString("displayName")).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', textBlock = """
            42      | displayName must be a string.
            "  "    | displayName must not be blank.
            """)
    void an_optional_string_of_another_type_or_blank_is_invalid_value(
            String value, String detail) {
        refused(() -> user("\"displayName\":" + value).optionalString("displayName"),
                "invalidValue", detail);
    }

    @ParameterizedTest
    @CsvSource({"true, false, true", "false, true, false"})
    void an_optional_boolean_is_the_boolean_given(
            String value, boolean whenAbsent, boolean expected) {
        assertThat(user("\"active\":" + value).optionalBoolean("active", whenAbsent))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', textBlock = """
            ''                | true
            ''                | false
            '"active":null'   | true
            '"active":null'   | false
            """)
    void an_optional_boolean_absent_or_null_is_its_default(
            String attributes, boolean whenAbsent) {
        assertThat(user(attributes).optionalBoolean("active", whenAbsent))
                .isEqualTo(whenAbsent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "{}"})
    void an_optional_boolean_of_another_type_is_invalid_value(String value) {
        refused(() -> user("\"active\":" + value).optionalBoolean("active", true),
                "invalidValue", "active must be a boolean.");
    }
}
