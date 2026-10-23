package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** RFC 7644 §3.9 projection, over a document shaped like a rendered User. */
class ScimAttributeProjectionTests {

    private static Map<String, Object> document() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("schemas", List.of(ScimSchemas.USER));
        user.put("id", "8a5c1f4e-0000-4000-8000-000000000001");
        user.put("externalId", "701984");
        user.put("userName", "bjensen");
        user.put("name", new LinkedHashMap<>(Map.of(
                "givenName", "Barbara", "familyName", "Jensen")));
        user.put("active", true);
        user.put("emails", List.of(new LinkedHashMap<>(Map.of(
                "value", "bjensen@example.com", "type", "work"))));
        user.put("meta", new LinkedHashMap<>(Map.of("resourceType", "User")));
        return user;
    }

    /**
     * A sub-attribute path against a MULTI-VALUED complex attribute projects every element.
     *
     * <p>{@code name.givenName} and {@code emails.value} take different branches: one
     * transforms a single object, the other each object in a list. A projection that handled
     * only the first would return {@code emails} whole, leaking the {@code type} and
     * {@code primary} of every address a client asked not to receive.
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_sub_attribute_of_a_multi_valued_attribute_projects_every_element() {
        Map<String, Object> user = document();
        user.put("emails", List.of(
                new LinkedHashMap<>(Map.of(
                        "value", "bjensen@example.com", "type", "work", "primary", true)),
                new LinkedHashMap<>(Map.of(
                        "value", "babs@jensen.org", "type", "home", "primary", false))));

        Map<String, Object> projected = ScimAttributeProjection.ofUser("emails.value", null).apply(user);

        List<Map<String, Object>> emails = (List<Map<String, Object>>) projected.get("emails");
        assertThat(emails).hasSize(2);
        assertThat(emails.get(0)).containsExactly(Map.entry("value", "bjensen@example.com"));
        assertThat(emails.get(1)).containsExactly(Map.entry("value", "babs@jensen.org"));
    }

    @Test
    void no_parameters_render_the_whole_document() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser(null, null).apply(document());

        assertThat(projected).isEqualTo(document());
    }

    @Test
    void the_two_parameters_are_mutually_exclusive() {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser("userName", "meta"))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(refusal -> {
                    ScimErrorException error = (ScimErrorException) refusal;
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.scimType()).isEqualTo("invalidValue");
                });
    }

    @Test
    void requested_attributes_are_kept_beside_the_always_returned_ones() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("userName", null).apply(document());

        assertThat(projected).containsOnlyKeys("schemas", "id", "userName");
    }

    /**
     * The criterion: asking for the password yields a resource without it and no error.
     *
     * <p>Nothing here mentions the password. It is absent from the rendered document
     * because the attribute is declared {@code returned=never}, so the projection selects
     * nothing and returns the always-returned attributes — which is ordinary projection
     * behaviour applied to an attribute that is never there.
     */
    @Test
    void asking_for_the_password_yields_a_resource_without_one() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("password", null).apply(document());

        assertThat(projected).containsOnlyKeys("schemas", "id");
        assertThat(projected).doesNotContainKey("password");
    }

    @Test
    void an_excluded_attribute_is_removed_and_the_rest_remain() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser(null, "meta,emails").apply(document());

        assertThat(projected).doesNotContainKeys("meta", "emails");
        assertThat(projected).containsKeys("schemas", "id", "userName", "active", "externalId");
    }

    /** An always-returned attribute survives being excluded. */
    @Test
    void excluding_an_always_returned_attribute_does_not_remove_it() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser(null, "id,schemas").apply(document());

        assertThat(projected).containsKeys("id", "schemas");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_sub_attribute_path_narrows_a_complex_value() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("name.givenName", null).apply(document());

        assertThat((Map<String, Object>) projected.get("name")).containsOnlyKeys("givenName");
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_excluded_sub_attribute_is_removed_from_every_value_of_a_multi_valued_attribute() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser(null, "emails.type").apply(document());

        List<Map<String, Object>> emails = (List<Map<String, Object>>) projected.get("emails");
        assertThat(emails).singleElement().satisfies(email ->
                assertThat(email).containsOnlyKeys("value"));
    }

    /**
     * A complex attribute named WITHOUT a sub-path is returned whole. This is the
     * difference between {@code attributes=name} and {@code attributes=name.givenName},
     * and it is the branch that decides whether asking for an attribute yields it or
     * yields an empty object.
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_complex_attribute_asked_for_whole_keeps_every_sub_attribute() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("name", null).apply(document());

        assertThat((Map<String, Object>) projected.get("name"))
                .containsOnlyKeys("givenName", "familyName")
                .containsEntry("givenName", "Barbara")
                .containsEntry("familyName", "Jensen");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_multi_valued_attribute_asked_for_whole_keeps_every_value_and_sub_attribute() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("emails", null).apply(document());

        List<Map<String, Object>> emails = (List<Map<String, Object>>) projected.get("emails");
        assertThat(emails).singleElement().satisfies(email ->
                assertThat(email).containsOnlyKeys("value", "type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_sub_attribute_path_narrows_every_value_of_a_multi_valued_attribute() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("emails.value", null).apply(document());

        List<Map<String, Object>> emails = (List<Map<String, Object>>) projected.get("emails");
        assertThat(emails).singleElement().satisfies(email ->
                assertThat(email).containsOnlyKeys("value"));
    }

    /**
     * A simple attribute is returned as its own value rather than being treated as a
     * complex one — the transform only applies to objects and lists of objects.
     */
    @Test
    void a_simple_attribute_asked_for_whole_is_returned_unchanged() {
        assertThat(ScimAttributeProjection.ofUser("userName", null).apply(document()))
                .containsEntry("userName", "bjensen");
    }

    /**
     * {@code meta} and {@code externalId} are projectable common attributes that no
     * resource schema declares, so they have no declared sub-attributes at all. A
     * sub-path against one is refused rather than silently matching nothing.
     *
     * <p>{@code groups} is deliberately NOT in this list: it is a declared multi-valued attribute
     * with four read-only sub-attributes, so a sub-path against it is legitimate. The positive case is asserted below, so the pair cannot both drift.
     */
    @ParameterizedTest
    @ValueSource(strings = {"meta.resourceType", "id.value", "externalId.value"})
    void a_sub_path_on_an_attribute_with_no_declared_sub_attributes_is_refused(String path) {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser(path, null))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("Not a sub-attribute of");
    }

    /**
     * The reverse membership view's sub-attributes ARE projectable, now that the attribute is
     * declared: a connector that wants only the ids of a User's Groups can ask for them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"groups.value", "groups.display", "groups.type"})
    void a_sub_path_on_the_declared_groups_attribute_is_accepted(String path) {
        assertThat(ScimAttributeProjection.ofUser(path, null)).isNotNull();
    }

    /** An undeclared sub-attribute of a declared one is still refused. */
    @Test
    void an_undeclared_sub_attribute_of_groups_is_refused() {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser("groups.primary", null))
                .isInstanceOf(ScimErrorException.class)
                .hasMessageContaining("Not a sub-attribute of groups");
    }

    /**
     * A refusal echoes the sub-attribute lower-cased, for the same reason a top-level
     * refusal does: the echo must not read as a canonical spelling this service accepts.
     */
    @Test
    void a_refused_sub_attribute_is_echoed_lower_cased_and_not_as_sent() {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser("emails.LABEL", null))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .contains("Not a sub-attribute of emails: label")
                        .doesNotContain("LABEL"));
    }

    @Test
    void a_refused_top_level_attribute_is_echoed_lower_cased_and_not_as_sent() {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser("NickName", null))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .contains("nickname")
                        .doesNotContain("NickName"));
    }

    @Test
    void a_common_attribute_may_still_be_named_whole() {
        assertThat(ScimAttributeProjection.ofUser("meta", null).apply(document()))
                .containsKey("meta");
    }

    /** RFC 7644 §3.10: attribute names are case-insensitive. */
    @Test
    void attribute_names_are_matched_case_insensitively() {
        assertThat(ScimAttributeProjection.ofUser("USERNAME", null).apply(document()))
                .containsKey("userName");
    }

    /**
     * Sub-attribute names are case-insensitive too, and are resolved back to their
     * canonical spelling — the rendered document's keys are the ones being compared, so a
     * sub-path kept in the caller's casing would match nothing and silently empty the
     * attribute.
     */
    @Test
    @SuppressWarnings("unchecked")
    void sub_attribute_names_are_matched_case_insensitively_and_canonicalized() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser("name.GIVENNAME", null).apply(document());

        assertThat((Map<String, Object>) projected.get("name"))
                .containsOnlyKeys("givenName")
                .containsEntry("givenName", "Barbara");
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_excluded_sub_attribute_is_matched_case_insensitively() {
        Map<String, Object> projected =
                ScimAttributeProjection.ofUser(null, "name.FamilyName").apply(document());

        assertThat((Map<String, Object>) projected.get("name")).containsOnlyKeys("givenName");
    }

    /** A fully-qualified path carries the schema URI; the attribute after it is the same one. */
    @Test
    void a_schema_qualified_path_names_the_same_attribute() {
        assertThat(ScimAttributeProjection.ofUser(ScimSchemas.USER + ":userName", null)
                        .apply(document()))
                .containsOnlyKeys("schemas", "id", "userName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nickName", "title", "phoneNumbers", "name.nickName", "emails.label"})
    void a_path_this_service_does_not_implement_is_refused(String path) {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser(path, null))
                .isInstanceOf(ScimErrorException.class)
                .satisfies(refusal -> assertThat(((ScimErrorException) refusal).scimType())
                        .isEqualTo("invalidValue"));
    }

    /** A refusal names the attribute the caller asked for, and nothing else. */
    @Test
    void a_refusal_names_the_attribute_and_carries_no_other_value() {
        assertThatThrownBy(() -> ScimAttributeProjection.ofUser("nickName", null))
                .hasMessageContaining("nickname")
                .hasMessageNotContaining("bjensen");
    }

    /** Several paths in one parameter, whitespace tolerated. */
    @Test
    void a_comma_separated_list_selects_each_attribute() {
        assertThat(ScimAttributeProjection.ofUser("userName, active , emails", null)
                        .apply(document()))
                .containsOnlyKeys("schemas", "id", "userName", "active", "emails");
    }

    // ---- base search -----------------------------------------------------------------------------

    private static Map<String, Object> groupDocument() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("schemas", List.of(ScimSchemas.GROUP));
        group.put("id", "8a5c1f4e-0000-4000-8000-000000000002");
        group.put("displayName", "Engineering");
        group.put("members", List.of(new LinkedHashMap<>(Map.of("value", "m1", "type", "User"))));
        group.put("meta", new LinkedHashMap<>(Map.of("resourceType", "Group")));
        return group;
    }

    /**
     * An inclusion naming only User attributes leaves a Group its always-returned attributes —
     * not the whole Group, which is what "no projection" would mean.
     */
    @Test
    void a_base_search_inclusion_applies_each_path_to_the_type_that_has_it() {
        ScimAttributeProjection.Search projection =
                ScimAttributeProjection.forSearch("userName, members.value, displayName", null);

        assertThat(projection.user().apply(document())).containsOnlyKeys("schemas", "id", "userName");
        Map<String, Object> group = projection.group().apply(groupDocument());
        assertThat(group).containsOnlyKeys("schemas", "id", "members", "displayName");
        assertThat(ScimAttributeProjection.forSearch("userName", null).group().apply(groupDocument()))
                .containsOnlyKeys("schemas", "id");
    }

    @Test
    void a_base_search_exclusion_applies_each_path_to_the_type_that_has_it() {
        ScimAttributeProjection.Search projection =
                ScimAttributeProjection.forSearch(null, "emails,members,meta");

        assertThat(projection.user().apply(document())).doesNotContainKeys("emails", "meta")
                .containsKeys("userName", "name");
        assertThat(projection.group().apply(groupDocument())).doesNotContainKeys("members", "meta")
                .containsKey("displayName");
    }

    /** An empty entry in the list — a doubled or trailing comma — is skipped, not refused. */
    @Test
    void a_base_search_projection_skips_empty_list_entries() {
        ScimAttributeProjection.Search projection =
                ScimAttributeProjection.forSearch("userName,, displayName,", null);

        assertThat(projection.user().apply(document())).containsKey("userName");
        assertThat(projection.group().apply(groupDocument())).containsKey("displayName")
                .doesNotContainKey("members");
    }

    @Test
    void a_base_search_with_no_projection_renders_both_types_whole() {
        ScimAttributeProjection.Search projection = ScimAttributeProjection.forSearch(" ", null);

        assertThat(projection.user().apply(document())).isEqualTo(document());
        assertThat(projection.group().apply(groupDocument())).isEqualTo(groupDocument());
    }

    /** Qualified with one type's schema, a path belongs to that type only. */
    @Test
    void a_base_search_path_qualified_by_one_schema_applies_to_that_type_only() {
        ScimAttributeProjection.Search projection = ScimAttributeProjection.forSearch(
                ScimSchemas.GROUP + ":displayName", null);

        assertThat(projection.group().apply(groupDocument()))
                .containsOnlyKeys("schemas", "id", "displayName");
        assertThat(projection.user().apply(document())).containsOnlyKeys("schemas", "id");
    }

    @Test
    void a_base_search_path_neither_type_has_or_both_parameters_are_refused() {
        assertThatThrownBy(() -> ScimAttributeProjection.forSearch("userName,nickName", null))
                .isInstanceOfSatisfying(ScimErrorException.class,
                        refusal -> assertThat(refusal.scimType()).isEqualTo("invalidValue"));
        assertThatThrownBy(() -> ScimAttributeProjection.forSearch("userName", "meta"))
                .isInstanceOfSatisfying(ScimErrorException.class,
                        refusal -> assertThat(refusal.detail()).contains("mutually exclusive"));
    }

    /** A sub-path refused for one type does not leave a partial entry behind for it. */
    @Test
    void a_path_one_type_refuses_leaves_that_types_projection_untouched() {
        ScimAttributeProjection.Search projection =
                ScimAttributeProjection.forSearch("members.value", null);

        assertThat(projection.user().apply(document())).containsOnlyKeys("schemas", "id");
    }
}
