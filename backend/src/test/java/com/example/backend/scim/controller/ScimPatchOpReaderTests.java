package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.controller.ScimPatchOpReader.Op;
import com.example.backend.scim.controller.ScimPatchOpReader.Operation;
import com.example.backend.scim.controller.ScimPatchPathGrammar.Path;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code PatchOp} reading every resource shares: the envelope, {@code op}, {@code path}, the
 * path-less {@code remove}, and the classification of a path against the resource's schema. Each
 * rule is the same for a User and a Group, so the resource-neutral ones run against both schemas.
 * The operations are read back as {@link Operation}s, so what a resource then makes of them is no
 * part of these cases.
 */
class ScimPatchOpReaderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final ScimResourceSchema USER = ScimResourceSchema.of(ScimResourceType.USER);

    private static final ScimResourceSchema GROUP = ScimResourceSchema.of(ScimResourceType.GROUP);

    private static List<Operation> readBody(ScimResourceType type, String body) {
        return ScimPatchOpReader.read(
                body == null ? null : JSON.readTree(body),
                ScimResourceSchema.of(type),
                List::of);
    }

    private static List<Operation> read(ScimResourceType type, String operations) {
        return readBody(type,
                "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":" + operations + "}");
    }

    private static Operation one(ScimResourceType type, String operation) {
        List<Operation> read = read(type, "[" + operation + "]");
        assertThat(read).hasSize(1);
        return read.get(0);
    }

    private static void bodyRefused(
            ScimResourceType type, String body, String scimType, String detail) {
        assertThatThrownBy(() -> readBody(type, body))
                .as(body)
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status().value()).isEqualTo(400);
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.detail()).isEqualTo(detail);
                });
    }

    private static void refused(
            ScimResourceType type, String operations, String scimType, String detail) {
        bodyRefused(type, "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":" + operations + "}",
                scimType, detail);
    }

    // ---- the envelope ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void a_body_that_is_not_a_patch_op_is_invalid_syntax(ScimResourceType type) {
        String notAPatchOp = "The request body must be a SCIM PatchOp.";
        bodyRefused(type, null, "invalidSyntax", notAPatchOp);
        bodyRefused(type, "[]", "invalidSyntax", notAPatchOp);
        String exactly = "A PATCH body must declare exactly the schema " + PATCH_OP;
        for (String schemas : List.of(
                "[\"" + type.schemaUri() + "\"]",
                "[\"" + PATCH_OP + "\",\"" + PATCH_OP + "\"]",
                "[]",
                "{\"only\":\"" + PATCH_OP + "\"}",
                "\"" + PATCH_OP + "\"")) {
            bodyRefused(type, "{\"schemas\":" + schemas
                    + ",\"Operations\":[{\"op\":\"remove\",\"path\":\"externalId\"}]}",
                    "invalidSyntax", exactly);
        }
        bodyRefused(type, "{\"Operations\":[{\"op\":\"remove\",\"path\":\"externalId\"}]}",
                "invalidSyntax", exactly);
        String nonEmpty = "A PatchOp must carry a non-empty Operations array.";
        bodyRefused(type, "{\"schemas\":[\"" + PATCH_OP + "\"]}", "invalidSyntax", nonEmpty);
        refused(type, "[]", "invalidSyntax", nonEmpty);
        refused(type, "{\"op\":\"remove\",\"path\":\"externalId\"}", "invalidSyntax", nonEmpty);
        refused(type, "[\"remove\"]", "invalidSyntax", "Each PATCH operation must be an object.");
    }

    /** The per-request bound: 100 operations are read, the 101st makes the body a refusal. */
    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void a_patch_op_carries_at_most_one_hundred_operations(ScimResourceType type) {
        String operation = "{\"op\":\"remove\",\"path\":\"externalId\"}";
        assertThat(read(type, "[" + String.join(",", Collections.nCopies(100, operation)) + "]"))
                .hasSize(100);
        refused(type, "[" + String.join(",", Collections.nCopies(101, operation)) + "]",
                "invalidValue", "A PatchOp may carry at most 100 operations.");
    }

    /** Operations are read in order, each with its op, its parsed path and its value. */
    @Test
    void operations_are_read_in_order_with_their_op_path_and_value() {
        assertThat(read(ScimResourceType.USER, """
                [{"op":"add","path":"emails[type eq \\"work\\"].value","value":"a@b.example"},
                 {"op":"remove","path":"name.givenName"},
                 {"op":"replace","value":{"locale":"en-GB"}}]"""))
                .containsExactly(
                        new Operation(Op.ADD, new Path("emails", "type eq \"work\"", "value"),
                                JSON.readTree("\"a@b.example\"")),
                        new Operation(Op.REMOVE, new Path("name", null, "givenname"), null),
                        new Operation(Op.REPLACE, null, JSON.readTree("{\"locale\":\"en-GB\"}")));
    }

    /**
     * Each operation is handed to the resource before the next is read, so a refusal the resource
     * makes of an earlier operation is the answer, not a later operation's malformed path.
     */
    @Test
    void the_resource_reads_each_operation_before_the_next_is_read() {
        List<Operation> seen = new ArrayList<>();
        assertThatThrownBy(() -> ScimPatchOpReader.read(JSON.readTree("""
                {"schemas":["%s"],"Operations":[
                 {"op":"remove","path":"externalId"},
                 {"op":"remove","path":"bad!path"}]}""".formatted(PATCH_OP)), USER,
                operation -> {
                    seen.add(operation);
                    throw ScimErrorException.mutability("refused by the resource");
                }))
                .isInstanceOfSatisfying(ScimErrorException.class, refusal ->
                        assertThat(refusal.scimType()).isEqualTo("mutability"));
        assertThat(seen).containsExactly(
                new Operation(Op.REMOVE, new Path("externalid", null, null), null));
    }

    // ---- op ------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void op_is_matched_regardless_of_case(ScimResourceType type) {
        assertThat(one(type, "{\"op\":\"aDd\",\"path\":\"externalId\"}").op()).isEqualTo(Op.ADD);
        assertThat(one(type, "{\"op\":\"REMOVE\",\"path\":\"externalId\"}").op())
                .isEqualTo(Op.REMOVE);
        assertThat(one(type, "{\"op\":\"Replace\",\"path\":\"externalId\"}").op())
                .isEqualTo(Op.REPLACE);
    }

    /** A missing, null, non-string or unknown op is invalidSyntax, whatever the path names. */
    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void an_op_that_is_not_add_remove_or_replace_is_invalid_syntax(ScimResourceType type) {
        refused(type, "[{\"path\":\"externalId\"}]",
                "invalidSyntax", "A required attribute is missing: op");
        refused(type, "[{\"op\":null,\"path\":\"externalId\"}]",
                "invalidSyntax", "A required attribute is missing: op");
        refused(type, "[{\"op\":7,\"path\":\"externalId\"}]",
                "invalidSyntax", "op must be a string.");
        refused(type, "[{\"op\":\"Move\",\"path\":\"externalId\"}]",
                "invalidSyntax", "Unsupported PATCH op: move");
        refused(type, "[{\"op\":\"\"}]", "invalidSyntax", "Unsupported PATCH op: ");
        refused(type, "[{\"op\":\"move\",\"path\":7}]",
                "invalidSyntax", "Unsupported PATCH op: move");
    }

    /** An unknown op is echoed sanitized: a control character cannot forge a log. */
    @Test
    void an_unknown_op_is_echoed_without_control_characters() {
        refused(ScimResourceType.GROUP, "[{\"op\":\"mo\\u0007ve\",\"path\":\"externalId\"}]",
                "invalidSyntax", "Unsupported PATCH op: move");
    }

    // ---- path ----------------------------------------------------------------------------

    /** RFC 7644 §3.12: a path that is not a string is invalid or malformed. */
    @ParameterizedTest
    @ValueSource(strings = {"7", "true", "{\"attribute\":\"externalId\"}", "[\"externalId\"]"})
    void a_path_that_is_not_a_string_is_invalid_path(String path) {
        for (ScimResourceType type : ScimResourceType.values()) {
            for (String op : List.of("add", "remove", "replace")) {
                refused(type, "[{\"op\":\"" + op + "\",\"path\":" + path + ",\"value\":\"x\"}]",
                        "invalidPath", "path must be a string.");
            }
        }
    }

    /** The grammar has no whitespace around a path, on either resource. */
    @ParameterizedTest
    @ValueSource(strings = {" externalId", "externalId ", "\\texternalId", "externalId\\n"})
    void a_path_with_surrounding_whitespace_is_invalid_path(String path) {
        for (ScimResourceType type : ScimResourceType.values()) {
            assertThatThrownBy(() -> read(type,
                    "[{\"op\":\"remove\",\"path\":\"" + path + "\"}]"))
                    .as(type + " " + path)
                    .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                        assertThat(refusal.scimType()).isEqualTo("invalidPath");
                        assertThat(refusal.detail()).startsWith("Not a valid attribute path: ");
                    });
        }
    }

    /** Each resource's own core schema URN prefixes its paths; the other resource's does not. */
    @Test
    void a_path_may_carry_the_resources_own_schema_prefix_only() {
        assertThat(one(ScimResourceType.USER, "{\"op\":\"remove\",\"path\":\""
                + ScimSchemas.USER + ":externalId\"}").path())
                .isEqualTo(new Path("externalid", null, null));
        assertThat(one(ScimResourceType.GROUP, "{\"op\":\"remove\",\"path\":\""
                + ScimSchemas.GROUP + ":members[value eq \\\"m\\\"]\"}").path())
                .isEqualTo(new Path("members", "value eq \"m\"", null));
        refused(ScimResourceType.USER, "[{\"op\":\"remove\",\"path\":\""
                + ScimSchemas.GROUP + ":externalId\"}]", "invalidPath",
                "Not a valid attribute path:"
                        + " urn:ietf:params:scim:schemas:core:2.0:group:externalid");
        refused(ScimResourceType.GROUP, "[{\"op\":\"remove\",\"path\":\""
                + ScimSchemas.USER + ":externalId\"}]", "invalidPath",
                "Not a valid attribute path:"
                        + " urn:ietf:params:scim:schemas:core:2.0:user:externalid");
    }

    // ---- path-less operations ---------------------------------------------------------------

    /** RFC 7644 §3.5.2.2: a remove with no path — absent or null — is noTarget. */
    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void a_remove_without_a_path_is_no_target(ScimResourceType type) {
        refused(type, "[{\"op\":\"remove\"}]", "noTarget", "A remove operation requires a path.");
        refused(type, "[{\"op\":\"Remove\",\"path\":null,\"value\":{}}]",
                "noTarget", "A remove operation requires a path.");
    }

    /** A path-less add or replace is read with no path, for the resource to decide. */
    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void an_add_or_replace_without_a_path_is_read_without_one(ScimResourceType type) {
        assertThat(one(type, "{\"op\":\"add\",\"value\":{\"externalId\":\"e\"}}"))
                .isEqualTo(new Operation(Op.ADD, null, JSON.readTree("{\"externalId\":\"e\"}")));
        assertThat(one(type, "{\"op\":\"replace\",\"path\":null}"))
                .isEqualTo(new Operation(Op.REPLACE, null, null));
    }

    // ---- classifying a path against the schema --------------------------------------------

    @Test
    void a_path_names_the_schemas_attribute_in_its_canonical_spelling() {
        assertThat(ScimPatchOpReader.target(USER, new Path("displayname", null, null)).name())
                .isEqualTo("displayName");
        assertThat(ScimPatchOpReader.target(USER, new Path("emails", "type eq \"w\"", "value"))
                .name()).isEqualTo("emails");
        assertThat(ScimPatchOpReader.target(GROUP, new Path("MEMBERS", "value eq \"m\"", null))
                .name()).isEqualTo("members");
        assertThat(ScimPatchOpReader.target(GROUP, new Path("externalid", null, null)).name())
                .isEqualTo("externalId");
    }

    private static void targetRefused(
            ScimResourceSchema schema, Path path, String scimType, String detail) {
        assertThatThrownBy(() -> ScimPatchOpReader.target(schema, path))
                .as(path.toString())
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.scimType()).isEqualTo(scimType);
                    assertThat(refusal.detail()).isEqualTo(detail);
                });
    }

    /** A read-only attribute is mutability whatever the path selects inside it. */
    @Test
    void a_read_only_attribute_is_mutability_whatever_the_path_says_about_it() {
        targetRefused(USER, new Path("meta", null, "version"), "mutability", "meta is read-only.");
        targetRefused(USER, new Path("groups", "value eq \"g\"", null),
                "mutability", "groups is read-only.");
        targetRefused(GROUP, new Path("id", null, null), "mutability", "id is read-only.");
        targetRefused(GROUP, new Path("schemas", null, null), "mutability",
                "schemas is read-only.");
    }

    @Test
    void a_filter_or_sub_attribute_where_the_attribute_has_none_is_invalid_path() {
        targetRefused(USER, new Path("active", "value eq true", null), "invalidPath",
                "Only a multi-valued attribute accepts a value filter.");
        targetRefused(GROUP, new Path("displayname", "value eq \"x\"", null), "invalidPath",
                "Only a multi-valued attribute accepts a value filter.");
        targetRefused(USER, new Path("username", null, "first"), "invalidPath",
                "username has no sub-attributes.");
        targetRefused(GROUP, new Path("externalid", null, "value"), "invalidPath",
                "externalid has no sub-attributes.");
        targetRefused(GROUP, new Path("nickname", null, "first"), "invalidPath",
                "nickname has no sub-attributes.");
        targetRefused(GROUP, new Path("nickname", "value eq \"x\"", null), "invalidPath",
                "Only a multi-valued attribute accepts a value filter.");
    }

    /** An attribute the schema does not have is refused, naming the resource and the echo. */
    @Test
    void an_attribute_the_schema_does_not_have_is_invalid_path() {
        targetRefused(USER, new Path("nick\u0007name", null, null), "invalidPath",
                "This service does not implement the User attribute: nickname");
        targetRefused(GROUP, new Path("members2", null, null), "invalidPath",
                "This service does not implement the Group attribute: members2");
        targetRefused(GROUP, new Path("username", null, null), "invalidPath",
                "This service does not implement the Group attribute: username");
    }
}
