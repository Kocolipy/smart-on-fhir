package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The claim that discovery matches the implementation, as an assertion.
 *
 * <p>The schema document and the set of attributes a write accepts are derived from one
 * definition, {@link ScimResourceSchema}, so these tests are what makes that derivation
 * load-bearing: advertising an attribute nothing stores, or accepting one nothing advertises,
 * fails here. The whole-document assertions are the independent half: each expected document is
 * written out here by hand rather than generated from the definition it checks.
 *
 * <p>Both resource types are held to that standard, so a Group attribute advertised and not
 * implemented is caught exactly as a User one would be.
 */
class ScimSchemaDocumentsTests {

    private static final String BASE_URI = ScimTestUris.BASE_URI;

    private static final ScimResourceSchema USER = ScimResourceSchema.of(ScimResourceType.USER);

    private static final ScimResourceSchema GROUP = ScimResourceSchema.of(ScimResourceType.GROUP);

    /** The RFC 7643 §3.1 common attributes, written out rather than read off the definition. */
    private static final Set<String> COMMON_NAMES = Set.of("schemas", "id", "externalId", "meta");

    /**
     * Every attribute a write may set is advertised writable, and every advertised writable one
     * may be set. {@code externalId} is the one common attribute a write sets without a resource
     * schema declaring it, which is RFC 7643 §3.1, so it is named here rather than derived.
     *
     * <p>"Writable" is read out of the document's own {@code mutability}, not out of a list
     * written here: {@code groups} is advertised and is NOT writable, and the declaration that
     * says so is the same one a connector reads. The second assertion is the independent oracle:
     * the writable set, as this directory's profile has it, by name.
     */
    @Test
    void the_accepted_write_attributes_are_the_advertised_writable_ones() {
        Set<String> accepted = USER.writableNames();

        assertThat(accepted).containsExactlyInAnyOrderElementsOf(
                withCommonWritables(advertisedWritableNames(ScimSchemaDocuments.of(ScimResourceType.USER, BASE_URI))));
        assertThat(accepted).containsExactlyInAnyOrder(
                "userName", "name", "displayName", "preferredLanguage", "locale", "timezone",
                "active", "password", "emails", "externalId");
    }

    /**
     * The attributes a write ignores rather than refuses: the read-only common attributes, and
     * on a User the reverse membership view.
     */
    @Test
    void the_read_only_attributes_are_the_common_ones_and_on_a_user_the_groups_view() {
        assertThat(USER.readOnlyNames()).containsExactlyInAnyOrder("schemas", "id", "meta", "groups");
        assertThat(GROUP.readOnlyNames()).containsExactlyInAnyOrder("schemas", "id", "meta");
    }

    /**
     * The reverse membership view is advertised, wholly read-only. That a write then ignores it
     * (RFC 7644 §3.5.2) is {@code ScimAttributeProfileIntegrationTests}' claim.
     */
    @Test
    void the_reverse_groups_view_is_advertised_read_only() {
        Map<String, Object> groups = advertised("groups");

        assertThat(groups).containsEntry("mutability", "readOnly");
        assertThat(groups).containsEntry("multiValued", true);
        assertThat(groups).containsEntry("returned", "default");
        assertThat(subAttributeNames(groups))
                .containsExactly("value", "display", "$ref", "type");
        assertThat(subAttributes(groups))
                .allSatisfy(sub -> assertThat(sub).containsEntry("mutability", "readOnly"));
    }

    /**
     * An unimplemented core attribute is absent from the document. A service advertising
     * {@code phoneNumbers} and dropping them is worse than one that says it has none.
     *
     * <p>{@code groups} is not in this list: the reverse membership view is computed and
     * rendered, so it is advertised — as {@code readOnly}, which the test above asserts.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "nickName", "profileUrl", "title", "userType", "phoneNumbers", "ims", "photos",
        "addresses", "entitlements", "roles", "x509Certificates",
    })
    void an_unimplemented_core_attribute_is_not_advertised(String attribute) {
        assertThat(advertisedNames()).doesNotContain(attribute);
    }

    /** The common attributes are not schema attributes, and must not be advertised as any. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "externalId", "meta", "schemas"})
    void a_common_attribute_is_not_declared_by_the_resource_schema(String attribute) {
        assertThat(advertisedNames()).doesNotContain(attribute);
        assertThat(USER.names()).contains(attribute);
    }

    /**
     * Projection names the declared attributes and the common ones, with no special case
     * left for {@code groups}: it is a declared schema attribute now, so it is projectable
     * because the derivation says so rather than because a list names it.
     */
    @Test
    void every_declared_and_common_attribute_is_projectable() {
        assertThat(USER.names())
                .containsExactlyInAnyOrderElementsOf(union(
                        advertisedNames(), COMMON_NAMES));
        assertThat(USER.names()).contains("groups");
    }

    /**
     * The rendered schema document, in full, field for field.
     *
     * <p>This is the one assertion the other tests in this class cannot replace. They check
     * WHICH attributes are advertised; this checks what is said ABOUT each of them —
     * {@code type}, {@code multiValued}, {@code required}, {@code caseExact},
     * {@code mutability}, {@code returned}, {@code uniqueness} and the sub-attribute lists.
     * A connector reads exactly these values to decide whether it may write an attribute,
     * whether to match on it case-sensitively, and whether it is single- or multi-valued, so
     * a wrong flag here is a wrong integration everywhere — and it is invisible to a test
     * that only compares names.
     *
     * <p>Whole-document equality rather than a field-by-field walk, deliberately: an
     * attribute gaining a key nobody asserted is the failure mode this catches. The list is
     * compared in order, because RFC 7643 §4.1 gives the core User attributes an order and
     * a client may render them in it.
     */
    @Test
    void the_rendered_schema_document_is_exactly_this() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schemas", List.of(ScimSchemas.SCHEMA));
        expected.put("id", ScimSchemas.USER);
        expected.put("name", "User");
        expected.put("description", "SCIM core User, as implemented by this service.");
        expected.put("attributes", List.of(
                attribute("userName", "string", false, true, false, READ_WRITE, DEFAULT, "server", List.of()),
                attribute("name", "complex", false, false, false, READ_WRITE, DEFAULT, "none", List.of(
                        writable("formatted", "string"),
                        writable("familyName", "string"),
                        writable("givenName", "string"),
                        writable("middleName", "string"),
                        writable("honorificPrefix", "string"),
                        writable("honorificSuffix", "string"))),
                writable("displayName", "string"),
                writable("preferredLanguage", "string"),
                writable("locale", "string"),
                writable("timezone", "string"),
                writable("active", "boolean"),
                attribute("password", "string", false, false, true, "writeOnly", "never", "none", List.of()),
                attribute("emails", "complex", true, false, false, READ_WRITE, DEFAULT, "none", List.of(
                        writable("value", "string"),
                        with(writable("type", "string"),
                                "canonicalValues", List.of("work", "home", "other")),
                        writable("primary", "boolean"))),
                attribute("groups", "complex", true, false, false, READ_ONLY, DEFAULT, "none", List.of(
                        with(readOnly("value", "string"), "caseExact", true),
                        readOnly("display", "string"),
                        with(with(readOnly("$ref", "reference"), "caseExact", true),
                                "referenceTypes", List.of("Group")),
                        with(readOnly("type", "string"), "canonicalValues", List.of("direct"))))));
        expected.put("meta", Map.of(
                "resourceType", "Schema",
                "location", BASE_URI + "/Schemas/" + ScimSchemas.USER));

        assertThat(ScimSchemaDocuments.of(ScimResourceType.USER, BASE_URI)).isEqualTo(expected);
    }

    // --- the Group vocabulary, held to the same standard ---------------------------------

    @Test
    void the_accepted_group_write_attributes_are_the_advertised_writable_ones() {
        Set<String> accepted = GROUP.writableNames();

        assertThat(accepted).containsExactlyInAnyOrderElementsOf(
                withCommonWritables(advertisedWritableNames(ScimSchemaDocuments.of(ScimResourceType.GROUP, BASE_URI))));
        assertThat(accepted).containsExactlyInAnyOrder("displayName", "members", "externalId");
    }

    @Test
    void a_common_group_attribute_is_not_declared_by_the_group_schema() {
        assertThat(advertisedNames(ScimSchemaDocuments.of(ScimResourceType.GROUP, BASE_URI)))
                .doesNotContainAnyElementsOf(Set.of("id", "externalId", "meta", "schemas"));
        assertThat(GROUP.names())
                .containsAll(COMMON_NAMES);
    }

    @Test
    void every_declared_and_common_group_attribute_is_projectable() {
        assertThat(GROUP.names())
                .containsExactlyInAnyOrderElementsOf(union(
                        advertisedNames(ScimSchemaDocuments.of(ScimResourceType.GROUP, BASE_URI)),
                        COMMON_NAMES));
    }

    /**
     * {@code members} is writable at the top level with {@code value} as its only writable
     * sub-attribute, which is the advertised form of "the label is derived, not something you
     * set" — the other end of the same relation the User's read-only {@code groups} view
     * renders.
     */
    @Test
    void group_membership_is_written_by_value_alone() {
        Map<String, Object> members = advertised(ScimSchemaDocuments.of(ScimResourceType.GROUP, BASE_URI), "members");

        assertThat(members).containsEntry("mutability", "readWrite");
        assertThat(members).containsEntry("multiValued", true);
        assertThat(subAttributeNames(members)).containsExactly("value", "display", "$ref", "type");
        assertThat(subAttribute(members, "value")).containsEntry("mutability", "readWrite");
        assertThat(subAttribute(members, "display")).containsEntry("mutability", "readOnly");
        assertThat(subAttribute(members, "$ref")).containsEntry("mutability", "readOnly");
        assertThat(subAttribute(members, "type")).containsEntry("mutability", "readOnly");
    }

    /** The Group document, whole, for the reason the User document is asserted whole. */
    @Test
    void the_rendered_group_schema_document_is_exactly_this() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schemas", List.of(ScimSchemas.SCHEMA));
        expected.put("id", ScimSchemas.GROUP);
        expected.put("name", "Group");
        expected.put("description", "SCIM core Group, as implemented by this service.");
        expected.put("attributes", List.of(
                attribute("displayName", "string", false, true, false, READ_WRITE, DEFAULT, "server",
                        List.of()),
                attribute("members", "complex", true, false, false, READ_WRITE, DEFAULT, "none", List.of(
                        with(writable("value", "string"), "caseExact", true),
                        readOnly("display", "string"),
                        with(with(readOnly("$ref", "reference"), "caseExact", true),
                                "referenceTypes", List.of("User")),
                        with(readOnly("type", "string"), "canonicalValues", List.of("User"))))));
        expected.put("meta", Map.of(
                "resourceType", "Schema",
                "location", BASE_URI + "/Schemas/" + ScimSchemas.GROUP));

        assertThat(ScimSchemaDocuments.of(ScimResourceType.GROUP, BASE_URI)).isEqualTo(expected);
    }

    private static final String READ_WRITE = "readWrite";

    private static final String READ_ONLY = "readOnly";

    private static final String DEFAULT = "default";

    /** An ordinary single-valued, optional, case-insensitive, writable attribute. */
    private static Map<String, Object> writable(String name, String type) {
        return attribute(name, type, false, false, false, READ_WRITE, DEFAULT, "none", List.of());
    }

    /** The same shape, declared read-only: a derived value a write may not set. */
    private static Map<String, Object> readOnly(String name, String type) {
        return attribute(name, type, false, false, false, READ_ONLY, DEFAULT, "none", List.of());
    }

    /** {@code rendered} with one characteristic set — replaced in place, or appended after the rest. */
    private static Map<String, Object> with(Map<String, Object> rendered, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(rendered);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, Object> attribute(
            String name,
            String type,
            boolean multiValued,
            boolean required,
            boolean caseExact,
            String mutability,
            String returned,
            String uniqueness,
            List<Map<String, Object>> subAttributes) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("name", name);
        rendered.put("type", type);
        if (!subAttributes.isEmpty()) {
            rendered.put("subAttributes", subAttributes);
        }
        rendered.put("multiValued", multiValued);
        rendered.put("required", required);
        rendered.put("caseExact", caseExact);
        rendered.put("mutability", mutability);
        rendered.put("returned", returned);
        rendered.put("uniqueness", uniqueness);
        return rendered;
    }

    private static Set<String> advertisedNames() {
        return advertisedNames(ScimSchemaDocuments.of(ScimResourceType.USER, BASE_URI));
    }

    private static Set<String> advertisedNames(Map<String, Object> document) {
        return attributes(document).stream()
                .map(attribute -> (String) attribute.get("name"))
                .collect(Collectors.toSet());
    }

    /** The advertised attributes a client may write, read out of the document's mutability. */
    private static Set<String> advertisedWritableNames(Map<String, Object> document) {
        return attributes(document).stream()
                .filter(attribute -> !READ_ONLY.equals(attribute.get("mutability")))
                .map(attribute -> (String) attribute.get("name"))
                .collect(Collectors.toSet());
    }

    /** The one common attribute a write may set though no resource schema declares it. */
    private static Set<String> withCommonWritables(Set<String> advertised) {
        return union(advertised, Set.of("externalId"));
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet());
    }

    private static Map<String, Object> advertised(String name) {
        return advertised(ScimSchemaDocuments.of(ScimResourceType.USER, BASE_URI), name);
    }

    private static Map<String, Object> advertised(Map<String, Object> document, String name) {
        return attributes(document).stream()
                .filter(attribute -> name.equals(attribute.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributes(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subAttributes(Map<String, Object> attribute) {
        return (List<Map<String, Object>>) attribute.get("subAttributes");
    }

    private static List<String> subAttributeNames(Map<String, Object> attribute) {
        return subAttributes(attribute).stream()
                .map(sub -> (String) sub.get("name"))
                .toList();
    }

    private static Map<String, Object> subAttribute(Map<String, Object> attribute, String name) {
        return subAttributes(attribute).stream()
                .filter(sub -> name.equals(sub.get("name")))
                .findFirst()
                .orElseThrow();
    }
}
