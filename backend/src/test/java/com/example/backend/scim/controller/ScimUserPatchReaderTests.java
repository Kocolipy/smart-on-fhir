package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimEmailFilter;
import com.example.backend.scim.domain.ScimEmailFilter.Condition;
import com.example.backend.scim.domain.ScimEmailPart;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import com.example.backend.scim.domain.ScimUserPatchOperation.AddEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.EmailUpdate;
import com.example.backend.scim.domain.ScimUserPatchOperation.MergeName;
import com.example.backend.scim.domain.ScimUserPatchOperation.NamePart;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveEmailPart;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveExternalId;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveName;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveNamePart;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemovePassword;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveText;
import com.example.backend.scim.domain.ScimUserPatchOperation.ReplaceEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetActive;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetExternalId;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetPassword;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetText;
import com.example.backend.scim.domain.ScimUserPatchOperation.TextAttribute;
import com.example.backend.scim.domain.ScimUserPatchOperation.UpdateEmails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** A PATCH body read into operations, and every refusal that depends on the request alone. */
class ScimUserPatchReaderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<ScimUserPatchOperation> read(String operations) {
        return ScimUserPatchReader.readPatch(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":%s}""".formatted(operations)));
    }

    private static ScimUserPatchOperation one(String op, String path, String value) {
        String pathPart = path == null ? "" : ",\"path\":" + JSON.writeValueAsString(path);
        String valuePart = value == null ? "" : ",\"value\":" + value;
        List<ScimUserPatchOperation> read =
                read("[{\"op\":\"" + op + "\"" + pathPart + valuePart + "}]");
        assertThat(read).hasSize(1);
        return read.get(0);
    }

    private static void refused(String operations, int status, String scimType) {
        assertThatThrownBy(() -> read(operations))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(status);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                });
    }

    private static ScimEmailFilter typeIs(String type) {
        return new ScimEmailFilter(List.of(new Condition(ScimEmailPart.TYPE, type)));
    }

    // ---- the envelope ---------------------------------------------------------------------

    // The envelope, op and path rules are ScimPatchOpReader's, and ScimPatchOpReaderTests owns
    // them. The cases here prove a User PATCH is read through that reader, with the User schema.

    @Test
    void a_body_that_is_not_a_patch_op_is_invalid_syntax() {
        assertThatThrownBy(() -> ScimUserPatchReader.readPatch(JSON.readTree(
                "{\"Operations\":[{\"op\":\"remove\",\"path\":\"displayName\"}]}")))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal ->
                        assertThat(refusal.scimType()).isEqualTo("invalidSyntax"));
    }

    @Test
    void an_unknown_or_missing_op_is_invalid_syntax_and_op_is_case_insensitive() {
        refused("[{\"op\":\"move\",\"path\":\"displayName\"}]", 400, "invalidSyntax");
        refused("[{\"path\":\"displayName\"}]", 400, "invalidSyntax");
        assertThat(one("Replace", "displayName", "\"Ada\""))
                .isEqualTo(new SetText(TextAttribute.DISPLAY_NAME, "Ada"));
        assertThat(one("REMOVE", "displayName", null))
                .isEqualTo(new RemoveText(TextAttribute.DISPLAY_NAME));
        assertThat(one("aDd", "displayName", "\"Ada\""))
                .isEqualTo(new SetText(TextAttribute.DISPLAY_NAME, "Ada"));
    }

    // ---- paths ----------------------------------------------------------------------------

    @Test
    void every_single_valued_attribute_reads_case_insensitively_and_with_the_schema_prefix() {
        assertThat(one("replace", "USERNAME", "\"ada\""))
                .isEqualTo(new SetText(TextAttribute.USER_NAME, "ada"));
        assertThat(one("replace",
                "urn:ietf:params:scim:schemas:core:2.0:User:displayName", "\"Ada\""))
                .isEqualTo(new SetText(TextAttribute.DISPLAY_NAME, "Ada"));
        assertThat(one("replace", "preferredLanguage", "\"en\""))
                .isEqualTo(new SetText(TextAttribute.PREFERRED_LANGUAGE, "en"));
        assertThat(one("replace", "locale", "\"en-GB\""))
                .isEqualTo(new SetText(TextAttribute.LOCALE, "en-GB"));
        assertThat(one("replace", "timezone", "\"Europe/London\""))
                .isEqualTo(new SetText(TextAttribute.TIMEZONE, "Europe/London"));
        assertThat(one("replace", "active", "false")).isEqualTo(new SetActive(false));
        assertThat(one("replace", "password", "\"a-new-password\""))
                .isEqualTo(new SetPassword("a-new-password"));
        assertThat(one("remove", "password", null)).isEqualTo(new RemovePassword());
    }

    /**
     * {@code active} has no unassigned state to remove it to, and reading a remove as the create
     * default would reactivate a deactivated User, so the remove is refused, with or without the
     * schema prefix.
     */
    @ParameterizedTest
    @ValueSource(strings = {"active", "ACTIVE", "urn:ietf:params:scim:schemas:core:2.0:User:active"})
    void removing_active_is_a_mutability_refusal(String path) {
        refused("[{\"op\":\"remove\",\"path\":" + JSON.writeValueAsString(path) + "}]",
                400, "mutability");
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "emails..value", "emails[", "emails[]", "1userName",
            "name.givenName.x", "userName!", "emails[type eq \"work\"]x"})
    void a_malformed_path_or_an_unimplemented_attribute_is_invalid_path(String path) {
        refused("[{\"op\":\"replace\",\"path\":" + JSON.writeValueAsString(path)
                + ",\"value\":\"x\"}]", 400, "invalidPath");
    }

    @Test
    void a_filter_or_sub_attribute_where_the_attribute_has_none_is_invalid_path() {
        refused("[{\"op\":\"replace\",\"path\":\"userName[value eq \\\"x\\\"]\",\"value\":\"x\"}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":\"userName.first\",\"value\":\"x\"}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":\"name.nickname\",\"value\":\"x\"}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":\"emails.label\",\"value\":\"x\"}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":\"name[givenName eq \\\"x\\\"]\",\"value\":\"x\"}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":7,\"value\":\"x\"}]", 400, "invalidPath");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "meta", "groups", "schemas", "Groups"})
    void a_read_only_attribute_as_a_path_is_mutability(String path) {
        refused("[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"x\"}]",
                400, "mutability");
    }

    /**
     * Read-only is decided on the attribute the path names, before anything else the path says:
     * a sub-path or a value filter into a read-only attribute is still {@code mutability}, not
     * {@code invalidPath}, and so is each op and the schema-qualified spelling.
     */
    @ParameterizedTest
    @ValueSource(strings = {"meta.version", "META.lastModified", "groups.value", "id.value",
        "groups[value eq \\\"x\\\"]", "groups[type eq \\\"direct\\\"].display",
        "urn:ietf:params:scim:schemas:core:2.0:User:groups",
        "urn:ietf:params:scim:schemas:core:2.0:User:meta.version"})
    void a_path_into_a_read_only_attribute_is_mutability_whatever_it_selects(String path) {
        for (String op : List.of("add", "replace", "remove")) {
            refused("[{\"op\":\"" + op + "\",\"path\":\"" + path + "\",\"value\":\"x\"}]",
                    400, "mutability");
        }
    }

    /**
     * An attribute this service does not implement is {@code invalidPath} however it is
     * addressed — bare, with a sub-path, or with a value filter — and so is a sub-path or value
     * filter on a writable attribute whose schema gives it none.
     */
    @ParameterizedTest
    @ValueSource(strings = {"nickName", "nickName.first", "phoneNumbers[type eq \\\"work\\\"]",
        "phoneNumbers[type eq \\\"work\\\"].value", "members", "password.value",
        "password[value eq \\\"x\\\"]", "active[value eq true]", "externalId[value eq \\\"x\\\"]",
        "displayName.value"})
    void an_unknown_attribute_or_an_undeclared_sub_path_is_invalid_path(String path) {
        refused("[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"x\"}]",
                400, "invalidPath");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD",
        "urn:ietf:params:scim:schemas:core:2.0:User:password"})
    void the_write_only_password_is_set_and_removed_by_its_path(String path) {
        assertThat(one("replace", path, "\"s3cret-Pass\"")).isEqualTo(new SetPassword("s3cret-Pass"));
        assertThat(one("add", path, "\"s3cret-Pass\"")).isEqualTo(new SetPassword("s3cret-Pass"));
        assertThat(one("remove", path, null)).isEqualTo(new RemovePassword());
    }

    /** Sub-attribute names in a path, a filter and a filtered value are case-insensitive. */
    @Test
    void email_sub_attributes_match_case_insensitively_everywhere_they_are_named() {
        assertThat(one("replace", "EMAILS.VALUE", "\"a@example.com\""))
                .isEqualTo(new UpdateEmails(ScimEmailFilter.ALL,
                        new EmailUpdate("a@example.com", null, null)));
        assertThat(one("remove", "emails[TYPE eq \"work\"].Primary", null))
                .isEqualTo(new RemoveEmailPart(
                        new ScimEmailFilter(List.of(new Condition(ScimEmailPart.TYPE, "work"))),
                        ScimEmailPart.PRIMARY));
        assertThat(one("replace", "emails[Primary eq true]", "{\"primary\":false}"))
                .isEqualTo(new UpdateEmails(
                        new ScimEmailFilter(List.of(new Condition(ScimEmailPart.PRIMARY, true))),
                        new EmailUpdate(null, null, false)));
        assertThat(one("remove", "name.HONORIFICSUFFIX", null))
                .isEqualTo(new RemoveNamePart(NamePart.HONORIFIC_SUFFIX));
    }

    /**
     * A {@code name} or {@code emails} value, and a filtered {@code emails} value, read their
     * sub-attributes by canonical name whatever the case they are spelled in.
     */
    @Test
    void a_complex_value_reads_its_sub_attributes_whatever_their_case() {
        assertThat(one("replace", "name", "{\"GIVENNAME\":\"Ada\",\"FamilyName\":\"King\"}"))
                .isEqualTo(new MergeName(new ScimName(null, "King", "Ada", null, null, null)));
        assertThat(one("add", "emails",
                "[{\"VALUE\":\"a@b.example\",\"Type\":\"work\",\"PRIMARY\":true}]"))
                .isEqualTo(new AddEmails(List.of(new ScimEmail("a@b.example", "work", true))));
        assertThat(one("replace", "emails[type eq \"work\"]",
                "{\"VALUE\":\"a@b.example\",\"Type\":\"home\",\"PRIMARY\":false}"))
                .isEqualTo(new UpdateEmails(typeIs("work"),
                        new EmailUpdate("a@b.example", "home", false)));
    }

    /** A sub-attribute named twice in a value, in spellings that differ by case, is ambiguous. */
    @Test
    void a_sub_attribute_given_twice_in_a_value_is_invalid_syntax() {
        String twice = " is given more than once, in names that differ only by case.";
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"]\","
                + "\"value\":{\"type\":\"a\",\"TYPE\":\"b\"}}]", "invalidSyntax",
                "The attribute emails.type" + twice);
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"name\","
                + "\"value\":{\"GivenName\":\"a\",\"givenName\":\"b\"}}]", "invalidSyntax",
                "The attribute name.givenName" + twice);
    }

    /** A path-less value ignores read-only attributes however they are spelled. */
    @Test
    void a_pathless_operation_ignores_read_only_attributes_in_any_case() {
        assertThat(read("""
                [{"op":"add","value":{"GROUPS":[],"Meta":{},"ID":"x","SCHEMAS":[],
                  "locale":"en-GB"}}]"""))
                .containsExactly(new SetText(TextAttribute.LOCALE, "en-GB"));
    }

    /**
     * {@code externalId} is read-write: {@code add} and {@code replace} set it, {@code remove}
     * clears it, by the bare path and by the schema-qualified one, case-insensitively.
     */
    @ParameterizedTest
    @ValueSource(strings = {"externalId", "EXTERNALID",
        "urn:ietf:params:scim:schemas:core:2.0:User:externalId"})
    void external_id_is_set_and_removed_by_its_path(String path) {
        assertThat(read("[{\"op\":\"add\",\"path\":\"" + path + "\",\"value\":\"e1\"}]"))
                .containsExactly(new SetExternalId("e1"));
        assertThat(read("[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"e2\"}]"))
                .containsExactly(new SetExternalId("e2"));
        assertThat(read("[{\"op\":\"remove\",\"path\":\"" + path + "\"}]"))
                .containsExactly(new RemoveExternalId());
    }

    @Test
    void external_id_takes_a_non_blank_string_and_has_no_sub_attributes() {
        refused("[{\"op\":\"replace\",\"path\":\"externalId\",\"value\":7}]",
                400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"externalId\"}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\" \"}]",
                400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"externalId.value\",\"value\":\"x\"}]",
                400, "invalidPath");
    }

    /** RFC 7644 §3.5.2.2: a remove with no path fails with {@code noTarget}. */
    @Test
    void a_remove_without_a_path_is_no_target() {
        refused("[{\"op\":\"remove\"}]", 400, "noTarget");
    }

    // ---- values ---------------------------------------------------------------------------

    @Test
    void a_value_of_the_wrong_type_or_a_missing_value_is_invalid_value() {
        refused("[{\"op\":\"replace\",\"path\":\"userName\",\"value\":7}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"userName\"}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"userName\",\"value\":\" \"}]",
                400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"active\",\"value\":\"false\"}]",
                400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"name\",\"value\":\"Ada\"}]",
                400, "invalidValue");
        refused("[{\"op\":\"add\",\"path\":\"emails\",\"value\":{\"value\":\"a@b.example\"}}]",
                400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"]\","
                + "\"value\":\"a@b.example\"}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"]\","
                + "\"value\":{\"label\":\"x\"}}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].primary\","
                + "\"value\":\"true\"}]", 400, "invalidValue");
    }

    // ---- name -----------------------------------------------------------------------------

    @Test
    void name_reads_as_a_merge_of_the_named_parts_or_a_removal() {
        assertThat(one("replace", "name", "{\"givenName\":\"Ada\",\"familyName\":\"King\"}"))
                .isEqualTo(new MergeName(new ScimName(null, "King", "Ada", null, null, null)));
        assertThat(one("remove", "name", null)).isEqualTo(new RemoveName());
        assertThat(one("remove", "name.middleName", null))
                .isEqualTo(new RemoveNamePart(NamePart.MIDDLE_NAME));
    }

    @Test
    void each_name_sub_attribute_path_sets_exactly_that_part() {
        assertThat(one("add", "name.formatted", "\"x\""))
                .isEqualTo(new MergeName(new ScimName("x", null, null, null, null, null)));
        assertThat(one("add", "name.familyName", "\"x\""))
                .isEqualTo(new MergeName(new ScimName(null, "x", null, null, null, null)));
        assertThat(one("add", "name.givenName", "\"x\""))
                .isEqualTo(new MergeName(new ScimName(null, null, "x", null, null, null)));
        assertThat(one("add", "name.MIDDLENAME", "\"x\""))
                .isEqualTo(new MergeName(new ScimName(null, null, null, "x", null, null)));
        assertThat(one("add", "name.honorificPrefix", "\"x\""))
                .isEqualTo(new MergeName(new ScimName(null, null, null, null, "x", null)));
        assertThat(one("add", "name.honorificSuffix", "\"x\""))
                .isEqualTo(new MergeName(new ScimName(null, null, null, null, null, "x")));
    }

    // ---- emails ---------------------------------------------------------------------------

    @Test
    void emails_read_as_add_replace_or_remove_of_the_whole_list() {
        ScimEmail work = new ScimEmail("ada@work.example", "work", true);
        String array = "[{\"value\":\"ada@work.example\",\"type\":\"work\",\"primary\":true}]";

        assertThat(one("add", "emails", array)).isEqualTo(new AddEmails(List.of(work)));
        assertThat(one("replace", "emails", array)).isEqualTo(new ReplaceEmails(List.of(work)));
        assertThat(one("remove", "emails", null))
                .isEqualTo(new RemoveEmails(ScimEmailFilter.ALL));
    }

    @Test
    void a_filtered_emails_path_reads_its_filter_and_its_target() {
        assertThat(one("remove", "emails[type eq \"work\"]", null))
                .isEqualTo(new RemoveEmails(typeIs("work")));
        assertThat(one("replace", "emails[type eq \"work\"]",
                "{\"value\":\"a@b.example\",\"primary\":false}"))
                .isEqualTo(new UpdateEmails(typeIs("work"),
                        new EmailUpdate("a@b.example", null, false)));
        assertThat(one("replace", "emails[type eq \"work\"]", "{\"type\":\"home\"}"))
                .isEqualTo(new UpdateEmails(typeIs("work"), new EmailUpdate(null, "home", null)));
        assertThat(one("replace", "emails[type eq \"work\"].value", "\"a@b.example\""))
                .isEqualTo(new UpdateEmails(typeIs("work"),
                        new EmailUpdate("a@b.example", null, null)));
        assertThat(one("add", "emails[type eq \"work\"].type", "\"home\""))
                .isEqualTo(new UpdateEmails(typeIs("work"), new EmailUpdate(null, "home", null)));
        assertThat(one("replace", "emails[type eq \"work\"].primary", "true"))
                .isEqualTo(new UpdateEmails(typeIs("work"), new EmailUpdate(null, null, true)));
        assertThat(one("remove", "emails[type eq \"work\"].type", null))
                .isEqualTo(new RemoveEmailPart(typeIs("work"), ScimEmailPart.TYPE));
        assertThat(one("remove", "emails.primary", null))
                .isEqualTo(new RemoveEmailPart(ScimEmailFilter.ALL, ScimEmailPart.PRIMARY));
    }

    // The value-filter grammar's own cases live in ScimPatchPathGrammarTests. The two below stay
    // here as proof that each kind of grammar refusal reaches a PATCH response unchanged.

    @Test
    void an_unterminated_string_in_a_filter_is_invalid_path() {
        refused("[{\"op\":\"remove\",\"path\":\"emails[type eq \\\"work]\"}]", 400, "invalidPath");
    }

    /** A conjunction with nothing after it ends the text; still a refusal, not a 500. */
    @Test
    void a_trailing_and_with_no_comparison_after_it_is_invalid_filter() {
        refused("[{\"op\":\"remove\",\"path\":\"emails[type eq \\\"work\\\" and]\"}]",
                400, "invalidFilter");
    }

    // ---- no path --------------------------------------------------------------------------

    /** Each attribute of the value applies as if it had been the path; read-only ones are ignored. */
    @Test
    void a_pathless_operation_expands_into_one_operation_per_attribute() {
        assertThat(read("""
                [{"op":"replace","value":{"displayName":"Ada","active":false,
                  "id":"ignored","meta":{},"groups":[],"schemas":[],"externalId":"x"}}]"""))
                .containsExactly(
                        new SetText(TextAttribute.DISPLAY_NAME, "Ada"),
                        new SetActive(false),
                        new SetExternalId("x"));
    }

    /** Each attribute keeps the operation's op: a path-less add appends emails, a replace replaces. */
    @Test
    void a_pathless_operation_applies_its_own_op_to_each_attribute() {
        ScimEmail work = new ScimEmail("ada@work.example", "work", false);
        String emails = "{\"emails\":[{\"value\":\"ada@work.example\",\"type\":\"work\"}]}";
        assertThat(read("[{\"op\":\"add\",\"value\":" + emails + "}]"))
                .containsExactly(new AddEmails(List.of(work)));
        assertThat(read("[{\"op\":\"replace\",\"value\":" + emails + "}]"))
                .containsExactly(new ReplaceEmails(List.of(work)));
    }

    @Test
    void a_pathless_operation_needs_an_object_of_plain_attribute_names() {
        refused("[{\"op\":\"replace\",\"value\":\"Ada\"}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\"}]", 400, "invalidValue");
        refused("[{\"op\":\"replace\",\"value\":{\"name.givenName\":\"Ada\"}}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"value\":{\"emails[type eq \\\"w\\\"]\":{}}}]",
                400, "invalidPath");
        refused("[{\"op\":\"replace\",\"value\":{\"title\":\"x\"}}]", 400, "invalidPath");
    }

    @Test
    void operations_are_read_in_order() {
        assertThat(read("""
                [{"op":"replace","path":"active","value":false},
                 {"op":"remove","path":"displayName"},
                 {"op":"add","path":"locale","value":"en-GB"}]"""))
                .containsExactly(
                        new SetActive(false),
                        new RemoveText(TextAttribute.DISPLAY_NAME),
                        new SetText(TextAttribute.LOCALE, "en-GB"));
    }

    // ---- which refusal, not only which type -------------------------------------------------

    /**
     * Several refusals share a SCIM type, so the type alone cannot tell which check fired. These
     * pin the detail as well: a check that stopped refusing would otherwise be hidden by a later
     * one refusing the same request for a different reason.
     */
    private static void refusedWith(String body, String scimType, String detail) {
        assertThatThrownBy(() -> ScimUserPatchReader.readPatch(JSON.readTree(body)))
                .as(body)
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(400);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.detail()).contains(detail);
                });
    }

    private static void operationRefusedWith(String operations, String scimType, String detail) {
        refusedWith("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":" + operations + "}", scimType, detail);
    }

    /** A JSON-null path is no path, so the value is read as an object of attributes. */
    @Test
    void a_null_path_is_read_as_no_path() {
        assertThat(read("[{\"op\":\"replace\",\"path\":null,\"value\":{\"locale\":\"en-GB\"}}]"))
                .containsExactly(new SetText(TextAttribute.LOCALE, "en-GB"));
    }

    @Test
    void each_path_refusal_names_the_path_lower_cased_and_sanitized() {
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"Bad!Path\",\"value\":\"x\"}]",
                "invalidPath", "Not a valid attribute path: bad!path");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"NickName\",\"value\":\"x\"}]",
                "invalidPath", "does not implement the User attribute: nickname");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"USERNAME.first\",\"value\":\"x\"}]",
                "invalidPath", "username has no sub-attributes.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"META.lastModified\",\"value\":\"x\"}]",
                "mutability", "meta is read-only.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"Groups\",\"value\":[]}]",
                "mutability", "groups is read-only.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"active[value eq true]\","
                + "\"value\":true}]", "invalidPath", "multi-valued attribute accepts a value filter");
    }

    /** A path-less value's keys must be plain attribute names, and a refusal echoes one sanitized. */
    @Test
    void a_pathless_key_that_is_not_a_plain_name_is_refused_as_such() {
        operationRefusedWith("[{\"op\":\"replace\",\"value\":{\"Name.givenName\":\"Ada\"}}]",
                "invalidPath", "Not an attribute name: name.givenname");
        operationRefusedWith("[{\"op\":\"replace\",\"value\":{\"emails[type eq \\\"w\\\"]\":{}}}]",
                "invalidPath", "Not an attribute name");
        operationRefusedWith("[{\"op\":\"replace\",\"value\":{\"1locale\":\"x\"}}]",
                "invalidPath", "Not an attribute name: 1locale");
    }

    @Test
    void each_value_refusal_names_the_attribute_and_what_it_needed() {
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":null}]",
                "invalidValue", "requires a value for displayName");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"active\"}]",
                "invalidValue", "active must be a boolean.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"name\"}]",
                "invalidValue", "name must be a complex value.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"name\",\"value\":\"Ada\"}]",
                "invalidValue", "name must be a complex value.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"name\",\"value\":null}]",
                "invalidValue", "name must be a complex value.");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":\"name.GIVENNAME\",\"value\":7}]",
                "invalidValue", "name.givenname must be a string.");
    }

    @Test
    void a_filtered_emails_value_must_be_an_object_of_known_sub_attributes() {
        String path = "\"emails[type eq \\\"work\\\"]\"";
        operationRefusedWith("[{\"op\":\"replace\",\"path\":" + path + "}]",
                "invalidValue", "requires an object value");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":" + path + ",\"value\":\"x\"}]",
                "invalidValue", "requires an object value");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":" + path + ",\"value\":{\"Label\":\"x\"}}]",
                "invalidValue", "emails sub-attribute: label");
        operationRefusedWith("[{\"op\":\"replace\",\"path\":" + path
                + ",\"value\":{\"primary\":\"yes\"}}]", "invalidValue", "emails.primary must be a boolean.");
    }

    // ---- the emails value filter: what the User contributes to the shared grammar ----------

    private static void emailFilterRefused(String filter, String detail) {
        assertThatThrownBy(() -> ScimUserPatchReader.emailFilter(filter))
                .as(filter)
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(400);
                    assertThat(refusal.scimType()).isEqualTo("invalidFilter");
                    assertThat(refusal.detail()).isEqualTo(detail);
                });
    }

    @Test
    void an_emails_sub_attribute_name_is_matched_case_insensitively() {
        assertThat(ScimUserPatchReader.emailPart("VALUE")).contains(ScimEmailPart.VALUE);
        assertThat(ScimUserPatchReader.emailPart("Type")).contains(ScimEmailPart.TYPE);
        assertThat(ScimUserPatchReader.emailPart("primary")).contains(ScimEmailPart.PRIMARY);
        assertThat(ScimUserPatchReader.emailPart("display")).isEmpty();
    }

    @Test
    void each_comparison_reads_as_a_condition_of_its_sub_attributes_type() {
        assertThat(ScimUserPatchReader.emailFilter("type eq \"work\""))
                .isEqualTo(new ScimEmailFilter(List.of(new Condition(ScimEmailPart.TYPE, "work"))));
        assertThat(ScimUserPatchReader.emailFilter("VALUE eq \"A@b.example\""))
                .isEqualTo(new ScimEmailFilter(
                        List.of(new Condition(ScimEmailPart.VALUE, "A@b.example"))));
        assertThat(ScimUserPatchReader.emailFilter("primary eq true and Primary eq false"))
                .isEqualTo(new ScimEmailFilter(List.of(
                        new Condition(ScimEmailPart.PRIMARY, true),
                        new Condition(ScimEmailPart.PRIMARY, false))));
        assertThat(ScimUserPatchReader.emailFilter("type eq null"))
                .isEqualTo(new ScimEmailFilter(List.of(new Condition(ScimEmailPart.TYPE, null))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"label eq \"x\"", "display eq \"x\"", "type eq \"w\" and x eq \"y\""})
    void a_sub_attribute_emails_does_not_have_is_unsupported(String filter) {
        emailFilterRefused(filter, "emails has no such sub-attribute.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"primary eq \"true\"", "primary eq null", "primary eq 1"})
    void primary_compares_only_with_a_boolean(String filter) {
        emailFilterRefused(filter, "primary compares with true or false.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"type eq 7", "value eq true", "type eq {}"})
    void value_and_type_compare_only_with_a_string_or_null(String filter) {
        emailFilterRefused(filter, "value and type compare with a quoted string.");
    }
}
