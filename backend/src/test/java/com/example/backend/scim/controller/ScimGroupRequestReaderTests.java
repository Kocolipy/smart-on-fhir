package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupReplacement;
import com.example.backend.scim.application.ScimGroupPatchOperation.RemoveAllMembers;
import com.example.backend.scim.application.ScimGroupPatchOperation.RemoveMembers;
import com.example.backend.scim.application.ScimGroupPatchOperation.SetDisplayName;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Group PatchOp read into operations, and the request-only refusals with the {@code scimType}
 * RFC 7644 §3.12 names for each — the same type a User PATCH gets for the same mistake.
 */
class ScimGroupRequestReaderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static List<ScimGroupPatchOperation> read(String operations) {
        return ScimGroupRequestReader.readPatch(JSON.readTree("""
                {"schemas":["%s"],"Operations":%s}""".formatted(PATCH_OP, operations)));
    }

    private static void refused(String operations, String scimType) {
        assertThatThrownBy(() -> read(operations))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(400);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                });
    }

    @Test
    void a_path_this_service_does_not_implement_is_invalid_path() {
        refused("[{\"op\":\"replace\",\"path\":\"nickName\",\"value\":\"x\"}]", "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":\"displayName[\",\"value\":\"x\"}]", "invalidPath");
    }

    @Test
    void a_members_value_path_on_anything_but_remove_is_invalid_path() {
        refused("[{\"op\":\"add\",\"path\":\"members[value eq \\\"" + UUID.randomUUID()
                + "\\\"]\"}]", "invalidPath");
        refused("[{\"op\":\"replace\",\"path\":\"members[value eq \\\"" + UUID.randomUUID()
                + "\\\"]\"}]", "invalidPath");
    }

    @Test
    void a_read_only_attribute_as_a_path_is_mutability() {
        for (String path : List.of("id", "meta", "schemas", "ID", "Meta")) {
            refused("[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"x\"}]",
                    "mutability");
        }
    }

    @Test
    void removing_the_required_display_name_is_mutability() {
        refused("[{\"op\":\"remove\",\"path\":\"displayName\"}]", "mutability");
    }

    @Test
    void a_remove_with_no_path_is_no_target_and_an_add_with_none_is_invalid_value() {
        refused("[{\"op\":\"remove\"}]", "noTarget");
        refused("[{\"op\":\"add\",\"value\":{\"displayName\":\"x\"}}]", "invalidValue");
        refused("[{\"op\":\"replace\",\"value\":{\"displayName\":\"x\"}}]", "invalidValue");
    }

    @Test
    void a_schema_qualified_path_is_the_unqualified_one() {
        assertThat(read("[{\"op\":\"replace\",\"path\":\"urn:ietf:params:scim:schemas:core:2.0:"
                + "Group:displayName\",\"value\":\"Eng\"}]"))
                .containsExactly(new SetDisplayName("Eng"));
        assertThat(read("[{\"op\":\"remove\",\"path\":\"URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:"
                + "GROUP:members\"}]"))
                .containsExactly(new RemoveAllMembers());
    }

    /**
     * {@code externalId} is read-write on a Group: {@code add} and {@code replace} set the caller's
     * alias and {@code remove} clears it, by the bare and the schema-qualified path alike.
     */
    @Test
    void external_id_is_set_and_removed_by_its_path() {
        for (String path : List.of("externalId", "EXTERNALID",
                "urn:ietf:params:scim:schemas:core:2.0:Group:externalId")) {
            assertThat(read("[{\"op\":\"add\",\"path\":\"" + path + "\",\"value\":\"e1\"}]"))
                    .containsExactly(new ScimGroupPatchOperation.SetExternalId("e1"));
            assertThat(read("[{\"op\":\"replace\",\"path\":\"" + path
                    + "\",\"value\":\"e2\"}]"))
                    .containsExactly(new ScimGroupPatchOperation.SetExternalId("e2"));
            assertThat(read("[{\"op\":\"remove\",\"path\":\"" + path + "\"}]"))
                    .containsExactly(new ScimGroupPatchOperation.RemoveExternalId());
        }
    }

    @Test
    void external_id_takes_a_non_blank_string_and_has_no_sub_attributes() {
        refused("[{\"op\":\"replace\",\"path\":\"externalId\",\"value\":7}]", "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"externalId\"}]", "invalidSyntax");
        refused("[{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\" \"}]",
                "invalidValue");
        refused("[{\"op\":\"replace\",\"path\":\"externalId.value\",\"value\":\"x\"}]",
                "invalidPath");
    }

    /**
     * An op that is not add, remove or replace is invalidSyntax whatever the path names, or with
     * no path at all, as it is for a User; so is a blank, non-string or missing one. The op is
     * matched case-insensitively.
     */
    @Test
    void an_op_that_is_not_add_remove_or_replace_is_invalid_syntax_whatever_the_path() {
        UUID member = UUID.randomUUID();
        for (String target : List.of(
                ",\"path\":\"displayName\",\"value\":\"x\"",
                ",\"path\":\"members\",\"value\":[{\"value\":\"" + member + "\"}]",
                ",\"path\":\"members[value eq \\\"" + member + "\\\"]\"",
                ",\"path\":\"externalId\",\"value\":\"x\"",
                ",\"path\":\"nickName\",\"value\":\"x\"",
                "")) {
            refused("[{\"op\":\"move\"" + target + "}]", "invalidSyntax");
        }
        refused("[{\"op\":\"\",\"path\":\"displayName\",\"value\":\"x\"}]", "invalidSyntax");
        refused("[{\"op\":7,\"path\":\"displayName\",\"value\":\"x\"}]", "invalidSyntax");
        refused("[{\"op\":null,\"path\":\"displayName\",\"value\":\"x\"}]", "invalidSyntax");
        refused("[{\"path\":\"displayName\",\"value\":\"x\"}]", "invalidSyntax");
        assertThat(read("[{\"op\":\"Replace\",\"path\":\"displayName\",\"value\":\"Eng\"}]"))
                .containsExactly(new SetDisplayName("Eng"));
    }

    /** A null op is missing, as an absent one is; an op of another type says it must be a string. */
    @Test
    void a_null_op_is_missing_and_a_non_string_op_must_be_a_string() {
        assertThatThrownBy(() -> read("[{\"op\":null,\"path\":\"displayName\",\"value\":\"x\"}]"))
                .hasMessage("A required attribute is missing: op");
        assertThatThrownBy(() -> read("[{\"path\":\"displayName\",\"value\":\"x\"}]"))
                .hasMessage("A required attribute is missing: op");
        assertThatThrownBy(() -> read("[{\"op\":7,\"path\":\"displayName\",\"value\":\"x\"}]"))
                .hasMessage("op must be a string.");
    }

    /**
     * {@code members} without a value path: {@code add} adds, {@code replace} replaces the whole
     * membership, {@code remove} with a value removes exactly those members, and {@code remove}
     * with a null value or none clears the membership.
     */
    @Test
    void members_are_added_replaced_removed_by_value_or_cleared() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String members = "[{\"value\":\"" + first + "\"},{\"value\":\"" + second + "\"}]";
        assertThat(read("[{\"op\":\"add\",\"path\":\"members\",\"value\":" + members + "}]"))
                .containsExactly(new ScimGroupPatchOperation.AddMembers(List.of(first, second)));
        assertThat(read("[{\"op\":\"replace\",\"path\":\"members\",\"value\":" + members + "}]"))
                .containsExactly(
                        new ScimGroupPatchOperation.ReplaceMembers(List.of(first, second)));
        assertThat(read("[{\"op\":\"remove\",\"path\":\"members\",\"value\":" + members + "}]"))
                .containsExactly(new RemoveMembers(List.of(first, second)));
        assertThat(read("[{\"op\":\"remove\",\"path\":\"members\",\"value\":null}]"))
                .containsExactly(new RemoveAllMembers());
        assertThat(read("[{\"op\":\"remove\",\"path\":\"members\"}]"))
                .containsExactly(new RemoveAllMembers());
    }

    /** An add or replace of members needs a value, and the value must name at least one member. */
    @Test
    void adding_or_replacing_members_needs_a_non_empty_value() {
        for (String op : List.of("add", "replace")) {
            refused("[{\"op\":\"" + op + "\",\"path\":\"members\"}]", "invalidSyntax");
            refused("[{\"op\":\"" + op + "\",\"path\":\"members\",\"value\":null}]",
                    "invalidSyntax");
            refused("[{\"op\":\"" + op + "\",\"path\":\"members\",\"value\":[]}]",
                    "invalidValue");
        }
    }

    /** An unknown op is echoed sanitized: a control character cannot forge a log. */
    @Test
    void an_unknown_op_is_echoed_without_control_characters() {
        assertThatThrownBy(() -> read(
                "[{\"op\":\"mo\\u0007ve\",\"path\":\"externalId\",\"value\":\"x\"}]"))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal ->
                        assertThat(refusal.getMessage()).contains("move").doesNotContain("\u0007"));
    }

    /** A PUT body's {@code externalId} is carried to the use case, and an omitted one is null. */
    @Test
    void a_replacement_carries_the_external_id_or_null_when_omitted() {
        UUID member = UUID.randomUUID();
        assertThat(ScimGroupRequestReader.readReplace(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"Eng","externalId":"e1","members":[{"value":"%s"}]}"""
                .formatted(member))))
                .isEqualTo(new ScimGroupReplacement("Eng", List.of(member), "e1"));
        assertThat(ScimGroupRequestReader.readReplace(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"Eng"}""")).externalId()).isNull();
    }

    /**
     * A PUT body round-tripped from a read keeps its read-only attributes, and they are ignored
     * (RFC 7644 §3.5.2): the replacement is what the writable attributes alone say.
     */
    @Test
    void a_replacement_ignores_the_read_only_attributes_it_carries() {
        assertThat(ScimGroupRequestReader.readReplace(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "id":"%s","meta":{"resourceType":"Group"},"displayName":"Eng"}"""
                .formatted(UUID.randomUUID()))))
                .isEqualTo(new ScimGroupReplacement("Eng", List.of(), null));
    }

    /** An attribute the Group schema does not define is refused, not silently dropped. */
    @Test
    void a_replacement_naming_an_attribute_the_schema_does_not_define_is_invalid_value() {
        assertThatThrownBy(() -> ScimGroupRequestReader.readReplace(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"Eng","nickName":"x"}""")))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo("invalidValue");
                    assertThat(refusal.getMessage())
                            .isEqualTo("This service does not implement the Group attribute: nickname");
                });
    }

    @Test
    void a_members_value_path_with_remove_names_one_member() {
        UUID member = UUID.randomUUID();
        assertThat(read("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + member
                + "\\\"]\"}]")).containsExactly(new RemoveMembers(List.of(member)));
    }

    @Test
    void a_body_that_does_not_declare_the_patch_op_schema_is_invalid_syntax() {
        for (String schemas : List.of(
                "[\"urn:ietf:params:scim:schemas:core:2.0:Group\"]",
                "[\"" + PATCH_OP + "\",\"" + PATCH_OP + "\"]",
                "[]",
                "{\"only\":\"" + PATCH_OP + "\"}",
                "\"" + PATCH_OP + "\"")) {
            assertThatThrownBy(() -> ScimGroupRequestReader.readPatch(JSON.readTree(
                    "{\"schemas\":" + schemas + ",\"Operations\":[{\"op\":\"remove\","
                            + "\"path\":\"members\"}]}")))
                    .as(schemas)
                    .isInstanceOfSatisfying(ScimErrorException.class, refusal ->
                            assertThat(refusal.scimType()).isEqualTo("invalidSyntax"));
        }
        assertThatThrownBy(() -> ScimGroupRequestReader.readPatch(JSON.readTree(
                "{\"Operations\":[{\"op\":\"remove\",\"path\":\"members\"}]}")))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal ->
                        assertThat(refusal.scimType()).isEqualTo("invalidSyntax"));
    }

    @Test
    void a_patch_op_carries_at_most_one_hundred_operations() {
        String operation = "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Eng\"}";
        assertThat(read("[" + String.join(",", Collections.nCopies(100, operation)) + "]"))
                .hasSize(100);
        refused("[" + String.join(",", Collections.nCopies(101, operation)) + "]",
                "invalidValue");
    }
}
