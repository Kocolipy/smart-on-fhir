package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.application.GroupMemberPage;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Optional;
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
 * A Group's direct members as the Groups projection reads them, against a real SQL Server: the
 * membership filter, the paging, and what the read costs.
 *
 * <p>The member page is selected by the directory's collection query — a count and a page, two
 * statements whatever they find — and its Users are then loaded through the User port. That load
 * is where a read could grow a statement per member (each User's emails, say, fetched apart), so
 * it is what this measures: a page of many members costs the store what a page of two does. Every
 * case runs in a transaction that rolls back.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@Transactional
class GroupMemberReadIntegrationTests {

    @Autowired
    private IdentityAdministrationService identities;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final String tag = "gmr-" + UUID.randomUUID().toString().substring(0, 8);

    /** Only this Group's direct members, in normalized {@code userName} order, a page at a time. */
    @Test
    void a_groups_direct_members_are_read_a_page_at_a_time_in_user_name_order() {
        ScimUser carol = user("carol");
        ScimUser alice = user("Alice");
        ScimUser bob = users.create(ScimIdentities.inactiveUser(tag + "-bob"));
        ScimUser outsider = user("aaron");
        ScimGroup group = groups.create(ScimIdentities.group(tag + "-nurses", carol, alice, bob));
        groups.create(ScimIdentities.group(tag + "-porters", outsider, carol));

        GroupMemberPage first = identities.groupMembers(group.id(), PageRequest.of(1, 2))
                .orElseThrow();
        GroupMemberPage second = identities.groupMembers(group.id(), PageRequest.of(3, 2))
                .orElseThrow();

        assertThat(List.of(first, second)).containsExactly(
                new GroupMemberPage(3, 1, List.of(
                        new GroupMemberPage.Member(alice.id(), tag + "-Alice", null, true),
                        new GroupMemberPage.Member(bob.id(), tag + "-bob", null, false))),
                new GroupMemberPage(3, 3, List.of(
                        new GroupMemberPage.Member(carol.id(), tag + "-carol", null, true))));
    }

    @Test
    void an_empty_group_reads_as_a_page_of_none_and_an_unknown_one_as_nothing() {
        ScimGroup empty = groups.create(ScimIdentities.group(tag + "-empty"));
        ScimGroup deleted = groups.create(ScimIdentities.group(tag + "-gone", user("x")));
        groups.deleteById(deleted.id(), ScimIdentities.NOW);

        assertThat(List.of(
                        identities.groupMembers(empty.id(), PageRequest.FIRST_PAGE),
                        identities.groupMembers(deleted.id(), PageRequest.FIRST_PAGE)))
                .containsExactly(
                        Optional.of(new GroupMemberPage(0, 1, List.of())),
                        Optional.empty());
    }

    /**
     * The read is not one statement per member: loading a page of forty members runs as many
     * statements as a page of two.
     */
    @Test
    void reading_a_page_of_many_members_costs_what_a_page_of_two_does() {
        ScimGroup few = groupOf(2);
        ScimGroup many = groupOf(40);
        identities.groupMembers(few.id(), PageRequest.FIRST_PAGE);

        long forFew = statementsFor(() -> identities.groupMembers(few.id(),
                PageRequest.FIRST_PAGE).orElseThrow().members().size(), 2);
        long forMany = statementsFor(() -> identities.groupMembers(many.id(),
                PageRequest.FIRST_PAGE).orElseThrow().members().size(), 40);

        assertThat(forMany).isEqualTo(forFew);
    }

    // ---- helpers --------------------------------------------------------------------------

    /**
     * How many statements the persistence layer prepared to run {@code read}, which must answer
     * {@code expected} members — so a read that silently found fewer cannot pass as cheaper.
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

    private ScimGroup groupOf(int size) {
        ScimUser[] members = IntStream.range(0, size)
                .mapToObj(index -> users.create(ScimIdentities.withDisplayName(
                        ScimIdentities.user(tag + "-" + size + "-" + index), "Member " + index)))
                .toArray(ScimUser[]::new);
        return groups.create(ScimIdentities.group(tag + "-of-" + size, members));
    }

    private ScimUser user(String name) {
        return users.create(ScimIdentities.user(tag + "-" + name));
    }
}
