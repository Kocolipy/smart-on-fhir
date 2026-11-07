package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupReplacement;
import com.example.backend.scim.application.ScimGroupPatchOperation.RemoveAllMembers;
import com.example.backend.scim.application.ScimGroupPatchOperation.RemoveMembers;
import com.example.backend.scim.application.ScimGroupPatchOperation.SetDisplayName;
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

    /** {@link #refused}, with the detail the refusal is rendered with. */
    private static void refused(String operations, String scimType, String detail) {
        assertThatThrownBy(() -> read(operations))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(400);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.getMessage()).isEqualTo(detail);
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
                + "\\\"]\"}]", "invalidPath", "A members value path is supported for remove only.");
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
        refused("[{\"op\":\"remove\",\"path\":\"displayName\"}]", "mutability",
                "displayName is required and cannot be removed.");
    }

    /**
     * A path-less remove is noTarget (RFC 7644 §3.5.2.2), and a path-less add or replace is
     * refused with the invalidSyntax the SCIM interoperability profile assigns to it.
     */
    @Test
    void a_remove_with_no_path_is_no_target_and_an_add_or_replace_with_none_is_invalid_syntax() {
        String required = "This service requires a path on each PATCH operation, naming"
                + " displayName, members or externalId.";
        refused("[{\"op\":\"remove\"}]", "noTarget");
        refused("[{\"op\":\"add\",\"value\":{\"displayName\":\"x\"}}]", "invalidSyntax", required);
        refused("[{\"op\":\"replace\",\"value\":{\"displayName\":\"x\"}}]", "invalidSyntax",
                required);
        refused("[{\"op\":\"replace\",\"path\":null}]", "invalidSyntax", required);
    }

    /**
     * Comparisons of {@code value} joined by {@code and}: naming one id they select that member,
     * naming two they select none — no member has two ids — which removes nothing.
     */
    @Test
    void a_members_filter_is_a_conjunction_of_value_comparisons() {
        UUID member = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertThat(read("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + member
                + "\\\" and Value eq \\\"" + member + "\\\"]\"}]"))
                .containsExactly(new RemoveMembers(List.of(member)));
        assertThat(read("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + member
                + "\\\" and value eq \\\"" + other + "\\\"]\"}]"))
                .containsExactly(new RemoveMembers(List.of()));
    }

    @Test
    void a_members_filter_compares_value_with_a_quoted_resource_id_only() {
        UUID member = UUID.randomUUID();
        refused("[{\"op\":\"remove\",\"path\":\"members[value ne \\\"" + member + "\\\"]\"}]",
                "invalidFilter", "Only the eq operator is supported in a value filter.");
        refused("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + member
                + "\\\" or value eq \\\"" + member + "\\\"]\"}]", "invalidFilter",
                "A compared value must be a JSON string, boolean or null.");
        refused("[{\"op\":\"remove\",\"path\":\"members[display eq \\\"Ada\\\"]\"}]",
                "invalidFilter", "A members filter may compare value only.");
        refused("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + member
                + "\\\" and type eq \\\"User\\\"]\"}]",
                "invalidFilter", "A members filter may compare value only.");
        refused("[{\"op\":\"remove\",\"path\":\"members[value eq 7]\"}]",
                "invalidFilter", "value compares with a quoted string.");
        refused("[{\"op\":\"remove\",\"path\":\"members[value eq null]\"}]",
                "invalidFilter", "value compares with a quoted string.");
        refused("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"not-an-id\\\"]\"}]",
                "invalidValue", "Each members value must be a resource id.");
    }

    /** A sub-attribute path into members is not implemented, with or without a value filter. */
    @Test
    void a_members_sub_attribute_path_is_invalid_path() {
        String detail = "This service does not implement a members sub-attribute path.";
        refused("[{\"op\":\"remove\",\"path\":\"members.value\"}]", "invalidPath", detail);
        refused("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + UUID.randomUUID()
                + "\\\"].display\"}]", "invalidPath", detail);
        refused("[{\"op\":\"replace\",\"path\":\"members.display\",\"value\":\"x\"}]",
                "invalidPath", detail);
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
                    "invalidValue", "A members value must name at least one member.");
        }
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

    /** {@code displayName} is required on a replacement, as it is on a create. */
    @Test
    void a_replacement_without_a_display_name_is_invalid_syntax() {
        assertThatThrownBy(() -> ScimGroupRequestReader.readReplace(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"members":[]}""")))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo("invalidSyntax");
                    assertThat(refusal.getMessage())
                            .isEqualTo("A required attribute is missing: displayName");
                });
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

    /**
     * The envelope, op and path rules are ScimPatchOpReader's, and ScimPatchOpReaderTests owns
     * them; this case proves a Group PATCH is read through that reader.
     */
    @Test
    void a_body_that_does_not_declare_the_patch_op_schema_is_invalid_syntax() {
        assertThatThrownBy(() -> ScimGroupRequestReader.readPatch(JSON.readTree(
                "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                        + "\"Operations\":[{\"op\":\"remove\",\"path\":\"members\"}]}")))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal ->
                        assertThat(refusal.scimType()).isEqualTo("invalidSyntax"));
    }

    /** A null value is a missing one, which is a malformed request rather than a wrong value. */
    @Test
    void a_null_display_name_or_external_id_value_is_missing() {
        refused("[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":null}]", "invalidSyntax",
                "This PATCH operation requires a value for displayName.");
        refused("[{\"op\":\"add\",\"path\":\"externalId\",\"value\":null}]", "invalidSyntax",
                "This PATCH operation requires a value for externalId.");
    }

    /** A members value is an array of objects, each naming a member by value and nothing unknown. */
    @Test
    void a_members_value_must_be_an_array_of_member_objects() {
        UUID member = UUID.randomUUID();
        refused("[{\"op\":\"add\",\"path\":\"members\",\"value\":{\"value\":\"" + member + "\"}}]",
                "invalidValue", "members must be an array.");
        refused("[{\"op\":\"add\",\"path\":\"members\",\"value\":[\"" + member + "\"]}]",
                "invalidValue", "each members value must be a complex value.");
        refused("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + member
                + "\",\"Nick\\u0007\":1}]}]", "invalidValue",
                "This service does not implement the members sub-attribute: nick");
        refused("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"display\":\"Ada\"}]}]",
                "invalidSyntax", "A required attribute is missing: value");
        refused("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":null}]}]",
                "invalidSyntax", "A required attribute is missing: value");
        assertThat(read("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + member
                + "\",\"display\":\"Ada\",\"$ref\":\"x\",\"type\":\"User\"}]}]"))
                .containsExactly(new ScimGroupPatchOperation.AddMembers(List.of(member)));
    }

    // ---- create and replace ---------------------------------------------------------------

    @Test
    void a_create_carries_its_display_name_members_and_external_id() {
        UUID member = UUID.randomUUID();
        assertThat(ScimGroupRequestReader.readCreate(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"Eng","externalId":"e1","members":[{"value":"%s"}]}"""
                .formatted(member))))
                .isEqualTo(new NewScimGroup("Eng", List.of(member), "e1"));
    }

    /** A null members or externalId is an absent one: no members, no alias. */
    @Test
    void a_replacement_reads_null_members_and_external_id_as_absent() {
        assertThat(ScimGroupRequestReader.readReplace(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"Eng","members":null,"externalId":null}""")))
                .isEqualTo(new ScimGroupReplacement("Eng", List.of(), null));
    }

    private static void bodyRefused(String body, String scimType, String detail) {
        assertThatThrownBy(() -> ScimGroupRequestReader.readCreate(
                body == null ? null : JSON.readTree(body)))
                .as(body)
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.getMessage()).isEqualTo(detail);
                });
    }

    /** A body declares exactly the one Group schema, as a non-empty array. */
    @Test
    void a_group_body_declares_exactly_the_group_schema() {
        String notAResource = "The request body must be a SCIM resource.";
        bodyRefused(null, "invalidSyntax", notAResource);
        bodyRefused("[]", "invalidSyntax", notAResource);
        String noSchemas = "A request body must declare its schemas as a non-empty array.";
        bodyRefused("{\"displayName\":\"Eng\"}", "invalidSyntax", noSchemas);
        bodyRefused("{\"schemas\":\"urn:ietf:params:scim:schemas:core:2.0:Group\","
                + "\"displayName\":\"Eng\"}", "invalidSyntax", noSchemas);
        bodyRefused("{\"schemas\":[],\"displayName\":\"Eng\"}", "invalidSyntax", noSchemas);
        bodyRefused("{\"schemas\":{\"only\":\"urn:ietf:params:scim:schemas:core:2.0:Group\"},"
                + "\"displayName\":\"Eng\"}", "invalidSyntax", noSchemas);
        String onlyGroup = "This service implements one Group schema only: "
                + "urn:ietf:params:scim:schemas:core:2.0:Group";
        bodyRefused("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"displayName\":\"Eng\"}", "invalidValue", onlyGroup);
        bodyRefused("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\","
                + "\"urn:example:extension\"],\"displayName\":\"Eng\"}", "invalidValue", onlyGroup);
    }
}
