package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.paging.domain.PageRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One collection query: what it holds, what it refuses, and how it is narrowed. */
class ScimQueryTests {

    private static final Set<ScimResourceType> USERS = Set.of(ScimResourceType.USER);

    private static final Set<ScimResourceType> BOTH =
            Set.of(ScimResourceType.USER, ScimResourceType.GROUP);

    @Test
    void a_query_names_at_least_one_resource_type() {
        assertThatThrownBy(() -> new ScimQuery(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScimQuery(Set.of(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_query_keeps_what_it_was_given_and_defaults_only_the_page() {
        ScimSort sort = ScimSort.of("userName", "descending", USERS);
        ScimFilter filter = ScimFilterParser.parse("userName eq \"ada\"", USERS);
        PageRequest page = new PageRequest(3, 7);

        ScimQuery given = new ScimQuery(USERS, filter, sort, page);
        assertThat(given.types()).containsExactly(ScimResourceType.USER);
        assertThat(given.filter()).isEqualTo(filter);
        assertThat(given.sort()).isEqualTo(sort);
        assertThat(given.page()).isEqualTo(page);

        assertThat(new ScimQuery(USERS, null, null, null).page())
                .isEqualTo(PageRequest.FIRST_PAGE);
    }

    /** The types are copied: a caller's set changing afterwards changes nothing. */
    @Test
    void a_query_copies_its_types() {
        Set<ScimResourceType> mutable = new HashSet<>(USERS);
        ScimQuery query = new ScimQuery(mutable, null, null, null);
        mutable.add(ScimResourceType.GROUP);

        assertThat(query.types()).containsExactly(ScimResourceType.USER);
    }

    @Test
    void the_textual_query_parses_its_filter_and_sort_against_its_types() {
        PageRequest page = new PageRequest(2, 5);

        ScimQuery query = ScimQuery.of(USERS, "userName eq \"ada\"", "userName", "descending", page);

        assertThat(query.types()).containsExactly(ScimResourceType.USER);
        assertThat(query.filter()).isEqualTo(ScimFilterParser.parse("userName eq \"ada\"", USERS));
        assertThat(query.sort()).isEqualTo(ScimSort.of("userName", "descending", USERS));
        assertThat(query.page()).isEqualTo(page);
    }

    @Test
    void the_textual_query_without_a_filter_has_none() {
        ScimQuery query = ScimQuery.of(USERS, null, null, null, null);

        assertThat(query.filter()).isNull();
        assertThat(query.sort()).isEqualTo(ScimSort.of(null, null, USERS));
        assertThat(query.page()).isEqualTo(PageRequest.FIRST_PAGE);
    }

    /** Narrowing keeps the filter, sort and page as parsed and drops only the types. */
    @Test
    void a_query_narrowed_to_readable_types_keeps_everything_else() {
        ScimFilter filter = ScimFilterParser.parse("displayName pr", BOTH);
        ScimSort sort = ScimSort.of("displayName", null, BOTH);
        PageRequest page = new PageRequest(1, 9);
        ScimQuery both = new ScimQuery(BOTH, filter, sort, page);

        ScimQuery narrowed = both.restrictedTo(
                new ConnectorTokenPermissions(Set.of(Permission.GROUP_READ)).readableTypes());

        assertThat(narrowed.types()).containsExactly(ScimResourceType.GROUP);
        assertThat(narrowed.filter()).isEqualTo(filter);
        assertThat(narrowed.sort()).isEqualTo(sort);
        assertThat(narrowed.page()).isEqualTo(page);
        assertThat(both.types()).as("the original is untouched").isEqualTo(BOTH);
        assertThatThrownBy(() -> both.restrictedTo(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code externalId} is the calling connector's own alias, and a {@code $ref} or
     * {@code meta.location} is built from the SCIM base URI it called: a query naming one of them
     * anywhere in its filter means nothing without a connector to ask on behalf of.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "externalId eq \"hr-1\"",
            "externalId pr",
            "meta.location pr",
            "groups.$ref eq \"https://scim.example/Groups/1\"",
            "groups[$ref eq \"https://scim.example/Groups/1\"]",
            "userName eq \"ada\" and not (externalId pr)",
            "externalId pr and userName eq \"ada\"",
            "userName eq \"ada\" or meta.location pr",
            "meta.location pr or userName eq \"ada\""})
    void a_filter_naming_a_connector_scoped_path_needs_a_connector(String filter) {
        ScimQuery query = new ScimQuery(USERS, ScimFilterParser.parse(filter, USERS), null, null);

        assertThat(query.needsConnector()).as(filter).isTrue();
    }

    /** A Group's members are referenced the same way a User's Groups are. */
    @Test
    void a_filter_naming_a_members_ref_needs_a_connector() {
        Set<ScimResourceType> groups = Set.of(ScimResourceType.GROUP);
        ScimQuery query = new ScimQuery(groups,
                ScimFilterParser.parse("members[$ref pr]", groups), null, null);

        assertThat(query.needsConnector()).isTrue();
    }

    /**
     * A member's identity and display name are the directory's own: only the {@code $ref} beside
     * them is built from the base URI, so a filter on the rest of the attribute needs no
     * connector.
     */
    @ParameterizedTest
    @ValueSource(strings = {"members[value pr]", "members[display pr]", "members[type pr]"})
    void a_filter_on_a_members_directory_owned_attributes_needs_no_connector(String filter) {
        Set<ScimResourceType> groups = Set.of(ScimResourceType.GROUP);
        ScimQuery query = new ScimQuery(groups,
                ScimFilterParser.parse(filter, groups), ScimSort.of("displayName", null, groups),
                null);

        assertThat(query.needsConnector()).as(filter).isFalse();
    }

    @Test
    void a_sort_by_a_connector_scoped_path_needs_a_connector() {
        ScimQuery query = new ScimQuery(USERS, null, ScimSort.of("externalId", null, USERS), null);

        assertThat(query.needsConnector()).isTrue();
    }

    /** The directory's own attributes read the same whoever asks. */
    @ParameterizedTest
    @ValueSource(strings = {
            "userName eq \"ada\"",
            "groups.value eq \"00000000-0000-4000-8000-000000000001\"",
            "emails[type eq \"work\" and value co \"@\"]",
            "meta.lastModified gt \"2024-01-01T00:00:00Z\" or not (active eq true)",
            "id pr and displayName pr"})
    void a_filter_on_the_directorys_own_attributes_needs_no_connector(String filter) {
        ScimQuery query = new ScimQuery(USERS, ScimFilterParser.parse(filter, USERS),
                ScimSort.of("userName", null, USERS), null);

        assertThat(query.needsConnector()).as(filter).isFalse();
    }

    @Test
    void a_query_with_no_filter_and_the_default_order_needs_no_connector() {
        assertThat(new ScimQuery(USERS, null, null, null).needsConnector()).isFalse();
    }

    /** A result's ids of one type are that type's hits alone, in the result's order. */
    @Test
    void a_results_ids_of_one_type_are_that_types_hits_in_order() {
        UUID ada = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
        UUID nurses = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
        UUID bea = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
        ScimQuery.Result result = new ScimQuery.Result(3, List.of(
                new ScimQuery.Hit(ScimResourceType.USER, ada),
                new ScimQuery.Hit(ScimResourceType.GROUP, nurses),
                new ScimQuery.Hit(ScimResourceType.USER, bea)));

        assertThat(List.of(result.idsOf(ScimResourceType.USER), result.idsOf(ScimResourceType.GROUP)))
                .containsExactly(List.of(ada, bea), List.of(nurses));
    }

    /**
     * The administrative name search over Users is the filter {@code userName co "<text>" or
     * displayName co "<text>"}, built as a value: the text, a quote and {@code LIKE} wildcards
     * included, is the comparison's literal, never filter-grammar text.
     */
    @Test
    void a_name_search_over_users_is_user_name_or_display_name_containing_the_text() {
        ScimFilter expected = ScimFilterParser.parse(
                "userName co \"o\\\"Brien 50%\" or displayName co \"o\\\"Brien 50%\"", USERS);

        assertThat(ScimFilter.nameContains(ScimResourceType.USER, "o\"Brien 50%"))
                .isEqualTo(expected);
    }

    /** A name search with no text is no search: there is no such filter to build. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void a_name_search_has_text(String text) {
        assertThatThrownBy(() -> ScimFilter.nameContains(ScimResourceType.USER, text))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Over Groups the name is the display name alone: a Group has no {@code userName}. */
    @Test
    void a_name_search_over_groups_is_display_name_containing_the_text() {
        Set<ScimResourceType> groups = Set.of(ScimResourceType.GROUP);

        assertThat(ScimFilter.nameContains(ScimResourceType.GROUP, "Nurse"))
                .isEqualTo(ScimFilterParser.parse("displayName co \"Nurse\"", groups));
    }

    /** Each resource type's wire name, endpoint, schema and Permissions. */
    @Test
    void each_resource_type_names_its_wire_spelling_and_permissions() {
        assertThat(ScimResourceType.USER.resourceTypeName()).isEqualTo("User");
        assertThat(ScimResourceType.USER.endpointName()).isEqualTo("Users");
        assertThat(ScimResourceType.USER.schemaUri())
                .isEqualTo("urn:ietf:params:scim:schemas:core:2.0:User");
        assertThat(ScimResourceType.USER.readPermission()).isEqualTo(Permission.USER_READ);
        assertThat(ScimResourceType.USER.writePermission()).isEqualTo(Permission.USER_WRITE);
        assertThat(ScimResourceType.GROUP.resourceTypeName()).isEqualTo("Group");
        assertThat(ScimResourceType.GROUP.endpointName()).isEqualTo("Groups");
        assertThat(ScimResourceType.GROUP.schemaUri())
                .isEqualTo("urn:ietf:params:scim:schemas:core:2.0:Group");
        assertThat(ScimResourceType.GROUP.readPermission()).isEqualTo(Permission.GROUP_READ);
        assertThat(ScimResourceType.GROUP.writePermission()).isEqualTo(Permission.GROUP_WRITE);
    }
}
