package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.ConnectorScopedQueryException;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimFilterParser;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimSort;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * The directory's collection query for a caller that is no connector and has no SCIM base URI —
 * an administrative read — against a real SQL Server: what it selects, in what order, and what
 * it refuses.
 *
 * <p>The connector's own query, over the same translation, is the SCIM suites'
 * ({@code ScimQueryProtocolIntegrationTests}); every case here runs in a transaction that rolls
 * back, so nothing it writes outlives it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@Transactional
class ScimQueryPersistenceAdapterIntegrationTests {

    private static final Set<ScimResourceType> USERS = Set.of(ScimResourceType.USER);

    @Autowired
    private AdministrativeQueryRepository queries;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    private final String tag = "qpa-" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * The direct members of one Group and nobody else — not a member of another Group, not a
     * User in no Group — in the Users' default order, normalized {@code userName}.
     */
    @Test
    void the_direct_members_of_a_group_are_selected_in_normalized_user_name_order() {
        ScimUser carol = user("Carol");
        ScimUser alice = user("alice");
        ScimUser bob = user("BOB");
        ScimUser outsider = user("dave");
        user("erin");
        ScimGroup group = groups.create(ScimIdentities.group(tag + "-g", carol, alice, bob));
        groups.create(ScimIdentities.group(tag + "-other", outsider, alice));

        ScimQuery.Result result = queries.query(membersOf(group, ScimPageRequest.FIRST_PAGE));

        assertThat(result.totalResults()).isEqualTo(3);
        assertThat(result.idsOf(ScimResourceType.USER))
                .containsExactly(alice.id(), bob.id(), carol.id());
    }

    /** The page is a one-based slice of that order; the total counts every member. */
    @Test
    void a_page_of_members_is_a_slice_of_the_order_and_the_total_counts_them_all() {
        List<ScimUser> members = List.of(user("m1"), user("m2"), user("m3"), user("m4"),
                user("m5"));
        ScimGroup group = groups.create(ScimIdentities.group(
                tag + "-g", members.toArray(ScimUser[]::new)));

        ScimQuery.Result result = queries.query(membersOf(group, new ScimPageRequest(2, 2)));

        assertThat(result.totalResults()).isEqualTo(5);
        assertThat(result.idsOf(ScimResourceType.USER))
                .containsExactly(members.get(1).id(), members.get(2).id());
    }

    @Test
    void a_group_with_no_members_selects_none() {
        user("lonely");
        ScimGroup group = groups.create(ScimIdentities.group(tag + "-empty"));

        ScimQuery.Result result = queries.query(membersOf(group, ScimPageRequest.FIRST_PAGE));

        assertThat(result).isEqualTo(new ScimQuery.Result(0, List.of()));
    }

    /** {@code count=0} still counts, and reads no page. */
    @Test
    void a_page_of_no_members_still_counts_them() {
        ScimGroup group = groups.create(ScimIdentities.group(tag + "-g", user("x1"), user("x2")));

        ScimQuery.Result result = queries.query(membersOf(group, new ScimPageRequest(1, 0)));

        assertThat(result).isEqualTo(new ScimQuery.Result(2, List.of()));
    }

    /**
     * {@code externalId}, a {@code $ref} and {@code meta.location} have a value only for a
     * connector that called a base URI: the query is refused rather than evaluated against
     * neither, and no statement is run for it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"externalId pr", "meta.location pr", "groups[$ref pr]"})
    void a_filter_on_a_connector_scoped_path_is_refused(String filter) {
        ScimQuery query = new ScimQuery(USERS, ScimFilterParser.parse(filter, USERS), null, null);

        assertThatExceptionOfType(ConnectorScopedQueryException.class)
                .isThrownBy(() -> queries.query(query));
    }

    @Test
    void a_sort_by_a_connector_scoped_path_is_refused() {
        ScimQuery query = new ScimQuery(USERS, null, ScimSort.of("externalId", null, USERS), null);

        assertThatExceptionOfType(ConnectorScopedQueryException.class)
                .isThrownBy(() -> queries.query(query));
    }

    // ---- helpers --------------------------------------------------------------------------

    private static ScimQuery membersOf(ScimGroup group, ScimPageRequest page) {
        return new ScimQuery(USERS, ScimFilter.directMemberOf(group.id()), null, page);
    }

    private ScimUser user(String name) {
        return users.create(ScimIdentities.user(tag + "-" + name));
    }
}
