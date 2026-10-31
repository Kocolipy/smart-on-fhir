package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.ConnectorScopedQueryException;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimFilterParser;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every directory query does, whatever answers it: the SQL Server adapter, and the in-memory
 * query store the search, listing and administration unit tests run against. Each runs it.
 *
 * <p>It covers what the in-memory store evaluates: the unfiltered, unsorted query in the default
 * order — Users by normalized {@code userName}, then Groups by normalized {@code displayName} — and
 * its page, for a connector and for an administrative caller; and the two filters an
 * administrative read builds as values, direct membership of one Group and a plain name search.
 * Every other filter and every sort the in-memory store refuses, so the SQL translation's own
 * suites cover them against SQL Server.
 *
 * <p>The store may hold resources no case wrote — the SQL Server database is shared with other
 * classes — so an unfiltered result is read as far as it names the case's own resources, whose
 * names begin with a digit so they sort ahead of every name the seed and the other suites write;
 * and its total as the rise the case's own writes made. Each filtered case selects only resources
 * it wrote, under a tag no other case shares.
 */
public abstract class ScimQueryRepositoryContract {

    private static final Set<ScimResourceType> USERS = Set.of(ScimResourceType.USER);

    private static final Set<ScimResourceType> GROUPS = Set.of(ScimResourceType.GROUP);

    private static final PageRequest WHOLE_PAGE =
            new PageRequest(1, PageRequest.MAX_COUNT);

    /** The query port a connector's search calls. */
    protected abstract ScimQueryRepository connectorQueries();

    /** The query port an administrative read calls, over the same store. */
    protected abstract AdministrativeQueryRepository administrativeQueries();

    /** The directory port over the same store, which arranges the Users queried. */
    protected abstract ScimUserRepository users();

    /** The Group port over the same store, which arranges the Groups queried. */
    protected abstract ScimGroupRepository groups();

    /** Sorts ahead of every name another suite writes, and is this case's alone. */
    private final String tag = "0qc-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    void the_unfiltered_query_lists_users_in_normalized_user_name_order() {
        ScimUser carol = user("Carol");
        ScimUser alice = user("alice");
        ScimUser bob = user("BOB");

        ScimQuery.Result result = administrativeQueries().query(
                new ScimQuery(USERS, null, null, WHOLE_PAGE));

        assertThat(ours(result, alice, bob, carol)).containsExactly(alice.id(), bob.id(), carol.id());
    }

    /** The total counts every User, not only the page's: three more Users count three more. */
    @Test
    void the_unfiltered_querys_total_counts_every_user() {
        ScimQuery firstOnly = new ScimQuery(USERS, null, null, new PageRequest(1, 1));
        long before = administrativeQueries().query(firstOnly).totalResults();
        user("one");
        user("two");
        user("three");

        assertThat(administrativeQueries().query(firstOnly).totalResults()).isEqualTo(before + 3);
    }

    /** Over both types, every User comes before every Group. */
    @Test
    void the_unfiltered_query_over_both_types_lists_users_before_groups() {
        ScimGroup group = groups().create(ScimIdentities.group(tag + "-a-group"));
        ScimUser user = user("z-user");

        ScimQuery.Result result = administrativeQueries().query(new ScimQuery(
                Set.of(ScimResourceType.USER, ScimResourceType.GROUP), null, null, WHOLE_PAGE));

        assertThat(result.hits().stream()
                        .filter(hit -> hit.id().equals(group.id()) || hit.id().equals(user.id()))
                        .toList())
                .containsExactly(new ScimQuery.Hit(ScimResourceType.USER, user.id()),
                        new ScimQuery.Hit(ScimResourceType.GROUP, group.id()));
    }

    @Test
    void the_unfiltered_query_lists_groups_in_normalized_display_name_order() {
        ScimGroup beta = groups().create(ScimIdentities.group(tag + "-Beta"));
        ScimGroup alpha = groups().create(ScimIdentities.group(tag + "-alpha"));

        ScimQuery.Result result = administrativeQueries().query(
                new ScimQuery(GROUPS, null, null, WHOLE_PAGE));

        assertThat(result.idsOf(ScimResourceType.GROUP).stream()
                        .filter(id -> id.equals(alpha.id()) || id.equals(beta.id()))
                        .toList())
                .containsExactly(alpha.id(), beta.id());
    }

    /** A connector's unfiltered query is the same list, in the same order. */
    @Test
    void a_connectors_unfiltered_query_lists_users_in_normalized_user_name_order() {
        ScimUser bob = user("bob");
        ScimUser alice = user("Alice");

        ScimQuery.Result result = connectorQueries().query(
                new ScimQuery(USERS, null, null, WHOLE_PAGE), UUID.randomUUID(),
                "https://backend.example/scim/v2");

        assertThat(ours(result, alice, bob)).containsExactly(alice.id(), bob.id());
    }

    /** The direct members of one Group and nobody else, in normalized {@code userName} order. */
    @Test
    void the_direct_members_of_a_group_are_selected_in_normalized_user_name_order() {
        ScimUser carol = user("Carol");
        ScimUser alice = user("alice");
        ScimUser outsider = user("dave");
        user("erin");
        ScimGroup group = groups().create(ScimIdentities.group(tag + "-g", carol, alice));
        groups().create(ScimIdentities.group(tag + "-other", outsider, alice));

        ScimQuery.Result result = administrativeQueries().query(
                membersOf(group, PageRequest.FIRST_PAGE));

        assertThat(result).isEqualTo(new ScimQuery.Result(2, List.of(
                new ScimQuery.Hit(ScimResourceType.USER, alice.id()),
                new ScimQuery.Hit(ScimResourceType.USER, carol.id()))));
    }

    /** A page is a one-based slice of the order; the total counts every member. */
    @Test
    void a_page_of_members_is_a_slice_of_the_order_and_the_total_counts_them_all() {
        List<ScimUser> members = List.of(user("m1"), user("m2"), user("m3"), user("m4"),
                user("m5"));
        ScimGroup group = groups().create(ScimIdentities.group(
                tag + "-g", members.toArray(ScimUser[]::new)));

        ScimQuery.Result result = administrativeQueries().query(
                membersOf(group, new PageRequest(2, 2)));

        assertThat(result).isEqualTo(new ScimQuery.Result(5, List.of(
                new ScimQuery.Hit(ScimResourceType.USER, members.get(1).id()),
                new ScimQuery.Hit(ScimResourceType.USER, members.get(2).id()))));
    }

    /** {@code count=0} still counts, and lists none. */
    @Test
    void a_page_of_no_members_still_counts_them() {
        ScimGroup group = groups().create(ScimIdentities.group(tag + "-g", user("x1"), user("x2")));

        ScimQuery.Result result = administrativeQueries().query(
                membersOf(group, new PageRequest(1, 0)));

        assertThat(result).isEqualTo(new ScimQuery.Result(2, List.of()));
    }

    @Test
    void a_group_with_no_members_selects_none() {
        user("lonely");
        ScimGroup group = groups().create(ScimIdentities.group(tag + "-empty"));

        assertThat(administrativeQueries().query(membersOf(group, PageRequest.FIRST_PAGE)))
                .isEqualTo(new ScimQuery.Result(0, List.of()));
    }

    /** A User's name search matches part of its {@code userName}, whatever the case. */
    @Test
    void a_user_name_search_matches_part_of_a_user_name_ignoring_case() {
        ScimUser match = user("Searchable");
        user("other");

        ScimQuery.Result result = administrativeQueries().query(new ScimQuery(USERS,
                ScimFilter.nameContains(ScimResourceType.USER, tag + "-SEARCH"), null, null));

        assertThat(result).isEqualTo(new ScimQuery.Result(1,
                List.of(new ScimQuery.Hit(ScimResourceType.USER, match.id()))));
    }

    /** A User's name search matches part of its {@code displayName} too. */
    @Test
    void a_user_name_search_matches_part_of_a_display_name() {
        ScimUser match = users().create(ScimIdentities.withDisplayName(
                ScimIdentities.user(tag + "-plain"), "Dr " + tag + " Findme"));
        user("unnamed");

        ScimQuery.Result result = administrativeQueries().query(new ScimQuery(USERS,
                ScimFilter.nameContains(ScimResourceType.USER, tag + " findme"), null, null));

        assertThat(result.idsOf(ScimResourceType.USER)).containsExactly(match.id());
    }

    @Test
    void a_group_name_search_matches_part_of_a_display_name_ignoring_case() {
        ScimGroup match = groups().create(ScimIdentities.group(tag + "-Nurses"));
        groups().create(ScimIdentities.group(tag + "-Doctors"));

        ScimQuery.Result result = administrativeQueries().query(new ScimQuery(GROUPS,
                ScimFilter.nameContains(ScimResourceType.GROUP, tag + "-nurse"), null, null));

        assertThat(result).isEqualTo(new ScimQuery.Result(1,
                List.of(new ScimQuery.Hit(ScimResourceType.GROUP, match.id()))));
    }

    @Test
    void a_name_search_matching_nothing_selects_none() {
        user("present");

        assertThat(administrativeQueries().query(new ScimQuery(USERS,
                ScimFilter.nameContains(ScimResourceType.USER, tag + "-absent"), null, null)))
                .isEqualTo(new ScimQuery.Result(0, List.of()));
    }

    /**
     * {@code externalId} has a value only for a connector: an administrative query on it is
     * refused rather than evaluated against no connector.
     */
    @Test
    void an_administrative_query_on_a_connector_scoped_path_is_refused() {
        ScimQuery query = new ScimQuery(USERS,
                ScimFilterParser.parse("externalId pr", USERS), null, null);

        assertThatExceptionOfType(ConnectorScopedQueryException.class)
                .isThrownBy(() -> administrativeQueries().query(query));
    }

    // ---- helpers --------------------------------------------------------------------------

    private static ScimQuery membersOf(ScimGroup group, PageRequest page) {
        return new ScimQuery(USERS, ScimFilter.directMemberOf(group.id()), null, page);
    }

    private ScimUser user(String name) {
        return users().create(ScimIdentities.user(tag + "-" + name));
    }

    /** The result's User ids, as far as they are these Users', in the result's order. */
    private static List<UUID> ours(ScimQuery.Result result, ScimUser... written) {
        Set<UUID> ids = new HashSet<>();
        for (ScimUser user : written) {
            ids.add(user.id());
        }
        return result.idsOf(ScimResourceType.USER).stream().filter(ids::contains).toList();
    }
}
