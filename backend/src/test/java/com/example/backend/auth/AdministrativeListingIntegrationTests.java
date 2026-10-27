package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.application.GroupPage;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentityPage;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Users and Groups projections as the Accounts page reads them, against a real SQL Server: a
 * page at a time, searched by name, totalled, and what a page costs.
 *
 * <p>Each page is selected by the directory's collection query — a count and a page, two
 * statements whatever they find — and its rows are then assembled: a page of Users with their
 * direct Groups in one batched membership read, a page of Groups with their members counted.
 * That assembly is where a read could grow a statement per row, so it is what the cost cases
 * measure. The shared database holds other tests' Users and Groups, so every case searches for its
 * own unique tag, which is also what scopes the totals. Every case runs in a transaction that
 * rolls back.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@Transactional
class AdministrativeListingIntegrationTests {

    @Autowired
    private IdentityAdministrationService identities;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final String tag = "alr" + UUID.randomUUID().toString().substring(0, 8);

    /** A page is a one-based slice of normalized {@code userName} order; the total counts all. */
    @Test
    void the_users_are_read_a_page_at_a_time_in_user_name_order_with_their_total() {
        user("carol");
        ScimUser alice = user("Alice");
        ScimUser bob = user("bob");
        user("dave");

        IdentityPage page = identities.listIdentities(tag, new ScimPageRequest(1, 2));

        assertThat(List.of(page.totalResults(), page.startIndex(), ids(page)))
                .containsExactly(4L, 1, List.of(alice.id(), bob.id()));
    }

    /**
     * The search matches a {@code userName} or a display name containing it, whatever the case of
     * either, and nothing else.
     */
    @Test
    void the_users_are_searched_by_user_name_or_display_name_ignoring_case() {
        ScimUser byName = user("Wren");
        ScimUser byDisplayName = users.create(ScimIdentities.withDisplayName(
                ScimIdentities.user(tag + "-x"), "Jenny WREN"));
        user("robin");

        IdentityPage page = identities.listIdentities(tag.toUpperCase() + "-wReN",
                ScimPageRequest.FIRST_PAGE);
        IdentityPage byDisplay = identities.listIdentities("jenny wren", ScimPageRequest.FIRST_PAGE);

        assertThat(List.of(ids(page), ids(byDisplay))).containsExactly(
                List.of(byName.id()), List.of(byDisplayName.id()));
    }

    /**
     * The search text is a literal, bound as a parameter: a {@code %} or an {@code _} matches only
     * itself and a quote is just a character, so no text typed into the search box changes what
     * the statement means.
     */
    @Test
    void a_search_for_wildcards_and_quotes_matches_them_literally() {
        ScimUser percent = user("50%off");
        user("50xoff");
        ScimUser quoted = user("o'brien\"x");
        user("oxbrien");

        assertThat(List.of(
                        ids(identities.listIdentities(tag + "-50%", ScimPageRequest.FIRST_PAGE)),
                        ids(identities.listIdentities("o'brien\"", ScimPageRequest.FIRST_PAGE)),
                        ids(identities.listIdentities(tag + "-o_b", ScimPageRequest.FIRST_PAGE))))
                .containsExactly(List.of(percent.id()), List.of(quoted.id()), List.of());
    }

    /** A search matching nothing is a page of none with a total of zero; so is a page past it. */
    @Test
    void a_search_matching_nothing_and_a_page_past_the_last_are_empty() {
        user("only");

        assertThat(List.of(
                        identities.listIdentities(tag + "-nobody", ScimPageRequest.FIRST_PAGE),
                        identities.listIdentities(tag, new ScimPageRequest(5, 2))))
                .containsExactly(new IdentityPage(0, 1, List.of()), new IdentityPage(1, 5, List.of()));
    }

    /**
     * Each row carries its User's direct Groups, in normalized display-name order, read for the
     * page's Users — a User in no Group carrying none — and the Superuser flag from them.
     */
    @Test
    void each_user_on_a_page_carries_its_direct_groups() {
        ScimUser ada = user("ada");
        ScimUser bea = user("bea");
        ScimGroup porters = groups.create(ScimIdentities.group(tag + "-porters", ada));
        ScimGroup nurses = groups.create(ScimIdentities.group(tag + "-Nurses", ada));

        assertThat(identities.listIdentities(tag, ScimPageRequest.FIRST_PAGE).users())
                .extracting(IdentitySummary::id, IdentitySummary::groups, IdentitySummary::admin)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ada.id(), List.of(
                                new IdentitySummary.DirectGroup(nurses.id(), tag + "-Nurses"),
                                new IdentitySummary.DirectGroup(porters.id(), tag + "-porters")),
                                false),
                        org.assertj.core.groups.Tuple.tuple(bea.id(), List.of(), false));
    }

    /**
     * The Groups column is one batched membership read for the page's Users, not one per User: a
     * page of forty Users, each in two Groups, runs as many statements as a page of two.
     */
    @Test
    void reading_a_page_of_many_users_costs_what_a_page_of_two_does() {
        String few = tag + "-few";
        String many = tag + "-many";
        usersInTwoGroups(few, 2);
        usersInTwoGroups(many, 40);
        identities.listIdentities(few, ScimPageRequest.FIRST_PAGE);

        long forFew = statementsFor(() -> identities.listIdentities(few, ScimPageRequest.FIRST_PAGE)
                .users().size(), 2);
        long forMany = statementsFor(() -> identities.listIdentities(many,
                ScimPageRequest.FIRST_PAGE).users().size(), 40);

        assertThat(forMany).isEqualTo(forFew);
    }

    /**
     * A page of Groups is a one-based slice of normalized display-name order, each counted, with
     * every match's total; the search ignores case.
     */
    @Test
    void the_groups_are_read_a_page_at_a_time_searched_by_display_name() {
        ScimUser ada = user("ada");
        groups.create(ScimIdentities.group(tag + "-Alpha"));
        ScimGroup beta = groups.create(ScimIdentities.group(tag + "-beta", ada, user("bea")));
        groups.create(ScimIdentities.group(tag + "-Gamma"));

        GroupPage page = identities.listGroups(tag.toUpperCase(), new ScimPageRequest(2, 1));

        assertThat(page).isEqualTo(new GroupPage(3, 2, List.of(
                new GroupSummary(beta.id(), tag + "-beta", 2, false, List.of()))));
    }

    /** Counting a page of many Groups runs as many statements as counting a page of two. */
    @Test
    void reading_a_page_of_many_groups_costs_what_a_page_of_two_does() {
        String few = tag + "-gfew";
        String many = tag + "-gmany";
        ScimUser member = user("member");
        IntStream.range(0, 2).forEach(index ->
                groups.create(ScimIdentities.group(few + "-" + index, member)));
        IntStream.range(0, 40).forEach(index ->
                groups.create(ScimIdentities.group(many + "-" + index, member)));
        identities.listGroups(few, ScimPageRequest.FIRST_PAGE);

        long forFew = statementsFor(() -> identities.listGroups(few, ScimPageRequest.FIRST_PAGE)
                .groups().size(), 2);
        long forMany = statementsFor(() -> identities.listGroups(many, ScimPageRequest.FIRST_PAGE)
                .groups().size(), 40);

        assertThat(forMany).isEqualTo(forFew);
    }

    // ---- helpers --------------------------------------------------------------------------

    /**
     * How many statements the persistence layer prepared to run {@code read}, which must answer
     * {@code expected} rows — so a read that silently found fewer cannot pass as cheaper.
     */
    private long statementsFor(IntSupplier read, int expected) {
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(read.getAsInt()).isEqualTo(expected);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    /** {@code size} Users named under {@code prefix}, every one a member of the same two Groups. */
    private void usersInTwoGroups(String prefix, int size) {
        ScimUser[] members = IntStream.range(0, size)
                .mapToObj(index -> users.create(ScimIdentities.withDisplayName(
                        ScimIdentities.user(prefix + "-" + index), "Member " + index)))
                .toArray(ScimUser[]::new);
        groups.create(ScimIdentities.group(prefix + "-one", members));
        groups.create(ScimIdentities.group(prefix + "-two", members));
    }

    private static List<UUID> ids(IdentityPage page) {
        return page.users().stream().map(IdentitySummary::id).toList();
    }

    private ScimUser user(String name) {
        return users.create(ScimIdentities.user(tag + "-" + name));
    }
}
