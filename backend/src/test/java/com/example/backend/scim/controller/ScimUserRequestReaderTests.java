package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.domain.ScimEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write-body reader, at the level of the JSON it is handed.
 *
 * <p>Driven directly rather than through the endpoint because the rules being asserted
 * are about malformed bodies, and a malformed body has no interesting journey through
 * the rest of the stack: the reader either refuses it or it never reaches a use case.
 * The provisioning test owns the well-formed path end to end.
 */
class ScimUserRequestReaderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode body(String json) {
        return JSON.readTree(json);
    }

    /** A minimal valid create, for tests that vary one attribute. */
    private static String withEmails(String emails) {
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"bjensen","emails":%s}""".formatted(emails);
    }

    /**
     * A PUT says whether it asserted {@code active}: an omitted or null one is not an assertion,
     * so the use case keeps the stored value instead of reactivating the User.
     */
    @Test
    void a_replacement_reports_whether_active_was_asserted() {
        String base = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"bjensen"%s}""";

        assertThat(ScimUserRequestReader.readReplace(body(base.formatted(""))).activeAsserted())
                .as("omitted").isFalse();
        assertThat(ScimUserRequestReader.readReplace(body(base.formatted(",\"active\":null")))
                .activeAsserted()).as("null").isFalse();
        assertThat(ScimUserRequestReader.readReplace(body(base.formatted(",\"active\":false"))))
                .satisfies(read -> {
                    assertThat(read.activeAsserted()).as("false").isTrue();
                    assertThat(read.profile().active()).isFalse();
                });
        assertThat(ScimUserRequestReader.readReplace(body(base.formatted(",\"active\":true")))
                .activeAsserted()).as("true").isTrue();
        assertThat(ScimUserRequestReader.readReplace(body(base.formatted(
                        ",\"externalId\":\"ext-1\",\"password\":\"a-long-password\""))))
                .satisfies(read -> {
                    assertThat(read.externalId()).isEqualTo("ext-1");
                    assertThat(read.password()).isEqualTo("a-long-password");
                });
    }

    @Test
    void a_body_with_no_emails_yields_no_emails() {
        NewScimUser command = ScimUserRequestReader.readCreate(body("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"bjensen"}"""));

        assertThat(command.profile().emails()).isEmpty();
    }

    @Test
    void an_explicitly_null_emails_yields_no_emails() {
        NewScimUser command = ScimUserRequestReader.readCreate(body(withEmails("null")));

        assertThat(command.profile().emails()).isEmpty();
    }

    @Test
    void an_emails_value_that_is_not_an_array_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(
                        body(withEmails("\"bjensen@example.com\""))))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("emails must be an array")
                .extracting(thrown -> ((ScimErrorException) thrown).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void an_emails_entry_that_is_not_a_complex_value_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(
                        body(withEmails("[\"bjensen@example.com\"]"))))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("each emails value must be a complex value");
    }

    @Test
    void an_emails_sub_attribute_this_service_does_not_implement_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body(withEmails(
                        "[{\"value\":\"bjensen@example.com\",\"display\":\"Barbara\"}]"))))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("does not implement the emails sub-attribute")
                .hasMessageContaining("display");
    }

    @Test
    void an_emails_entry_without_a_value_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(
                        body(withEmails("[{\"type\":\"work\"}]"))))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("A required attribute is missing: value");
    }

    @Test
    void an_emails_entry_is_read_with_its_type_and_primary_flag() {
        NewScimUser command = ScimUserRequestReader.readCreate(body(withEmails(
                "[{\"value\":\"bjensen@example.com\",\"type\":\"work\",\"primary\":true}]")));

        assertThat(command.profile().emails())
                .containsExactly(new ScimEmail("bjensen@example.com", "work", true));
    }

    @Test
    void an_emails_entry_defaults_to_not_primary_and_no_type() {
        NewScimUser command = ScimUserRequestReader.readCreate(
                body(withEmails("[{\"value\":\"bjensen@example.com\"}]")));

        assertThat(command.profile().emails())
                .containsExactly(new ScimEmail("bjensen@example.com", null, false));
    }

    @Test
    void every_emails_entry_is_read_in_the_order_sent() {
        NewScimUser command = ScimUserRequestReader.readCreate(body(withEmails("""
                [{"value":"work@example.com","type":"work"},
                 {"value":"home@example.com","type":"home"}]""")));

        assertThat(command.profile().emails())
                .containsExactly(
                        new ScimEmail("work@example.com", "work", false),
                        new ScimEmail("home@example.com", "home", false));
    }

    @Test
    void a_primary_flag_that_is_not_a_boolean_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body(withEmails(
                        "[{\"value\":\"bjensen@example.com\",\"primary\":\"yes\"}]"))))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("primary must be a boolean");
    }

    @Test
    void a_name_that_is_not_a_complex_value_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","name":"Barbara Jensen"}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("name must be a complex value");
    }

    @Test
    void an_explicitly_null_name_is_the_absent_name() {
        NewScimUser command = ScimUserRequestReader.readCreate(body("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"bjensen","name":null}"""));

        assertThat(command.profile().name())
                .isEqualTo(com.example.backend.scim.domain.ScimName.NONE);
    }

    @Test
    void a_name_sub_attribute_this_service_does_not_implement_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","name":{"nickName":"Barb"}}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("does not implement the name sub-attribute")
                .hasMessageContaining("nickname");
    }

    // --- the echoed attribute name, which is the one client-controlled string in a
    // --- refusal message (CWE-117)

    @Test
    void an_echoed_attribute_name_is_stripped_of_control_characters() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","ti\\ntle\\u0000":"x"}""")))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .contains("title")
                        .doesNotContain("\n")
                        .doesNotContain("\u0000"));
    }

    @Test
    void an_echoed_attribute_name_is_capped_so_a_huge_key_is_not_reflected_whole() {
        String huge = "z".repeat(500);

        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","%s":"x"}""".formatted(huge))))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .contains("z".repeat(64))
                        .doesNotContain("z".repeat(65)));
    }

    @Test
    void an_echoed_attribute_name_is_lower_cased_so_it_cannot_pass_as_canonical() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","TiTLe":"x"}""")))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .contains("title")
                        .doesNotContain("TiTLe"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "nickName", "roles", "x509Certificates"})
    void an_attribute_this_service_does_not_implement_is_refused(String attribute) {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","%s":"x"}""".formatted(attribute))))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("does not implement the User attribute");
    }

    /**
     * The reverse membership view is read-only, so a body carrying it is accepted and the
     * value dropped — RFC 7644 §3.5.2, which is what lets a client PUT back a User it read.
     * Refusing it would break that round-trip; storing it would let a write grant authority
     * through the wrong end of the relation, and there is no component on the create command
     * a membership could land in.
     */
    @Test
    void a_submitted_groups_view_is_ignored_rather_than_refused_or_stored() {
        NewScimUser command = ScimUserRequestReader.readCreate(body("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"bjensen",
                 "groups":[{"value":"8a5c1f4e-0000-4000-8000-000000000001",
                            "display":"Admins","type":"direct"}]}"""));

        assertThat(command.profile().userName()).isEqualTo("bjensen");
        assertThat(command.profile()).isEqualTo(new com.example.backend.scim.domain.ScimUserProfile(
                "bjensen",
                com.example.backend.scim.domain.ScimName.NONE,
                null,
                null,
                null,
                null,
                true,
                java.util.List.of()));
    }

    /** {@code id} and {@code meta} are ignored on a write for the same round-trip reason. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "meta"})
    void a_read_only_common_attribute_is_ignored_rather_than_refused(String attribute) {
        NewScimUser command = ScimUserRequestReader.readCreate(body("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"bjensen","%s":{"anything":"at all"}}""".formatted(attribute)));

        assertThat(command.profile().userName()).isEqualTo("bjensen");
    }

    @Test
    void a_string_attribute_carrying_a_non_string_value_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":42}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("userName must be a string");
    }

    @Test
    void a_string_attribute_carrying_a_blank_value_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"   "}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("userName must not be blank");
    }

    @Test
    void an_optional_string_attribute_is_held_to_the_same_two_rules() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","displayName":true}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("displayName must be a string");

        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"bjensen","displayName":""}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("displayName must not be blank");
    }

    @Test
    void a_missing_user_name_is_refused_as_a_missing_required_attribute() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"]}""")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("A required attribute is missing: userName");
    }

    @Test
    void a_body_that_is_not_an_object_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body("[]")))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("must be a SCIM resource");
    }

    @Test
    void a_null_body_is_refused() {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(null))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("must be a SCIM resource");
    }

    /**
     * A body declares exactly the one User schema, as a non-empty array: one declaring an
     * extension as well asserts attributes this service does not have.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"userName\":\"bjensen\"}",
        "{\"schemas\":\"urn:ietf:params:scim:schemas:core:2.0:User\",\"userName\":\"bjensen\"}",
        "{\"schemas\":[],\"userName\":\"bjensen\"}",
        "{\"schemas\":{\"only\":\"urn:ietf:params:scim:schemas:core:2.0:User\"},"
                + "\"userName\":\"bjensen\"}"})
    void a_body_not_declaring_its_schemas_as_a_non_empty_array_is_refused(String json) {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body(json)))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo("invalidSyntax");
                    assertThat(refusal.detail()).isEqualTo(
                            "A resource must declare its schemas as a non-empty array.");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[\"urn:ietf:params:scim:schemas:core:2.0:Group\"]",
        "[\"urn:ietf:params:scim:schemas:core:2.0:User\","
                + "\"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User\"]"})
    void a_body_declaring_any_schema_but_the_user_schema_alone_is_refused(String schemas) {
        assertThatThrownBy(() -> ScimUserRequestReader.readCreate(body(
                        "{\"schemas\":" + schemas + ",\"userName\":\"bjensen\"}")))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo("invalidValue");
                    assertThat(refusal.detail()).isEqualTo(
                            "This service implements one User schema only: "
                                    + "urn:ietf:params:scim:schemas:core:2.0:User");
                });
    }
}
