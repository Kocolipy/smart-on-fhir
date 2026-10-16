package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which resources each SCIM write advances, decided from plain membership sets with no database.
 *
 * <p>Every case the adapters describe is here, including the ones that have been missed or are
 * easy to miss: a Group created with members, a rename, and a member removed and re-added in the
 * same write. The integration suites observe the same rule end to end through ETags and
 * {@code meta.version}; these pin the decision itself.
 */
class RepresentationChangeTests {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Nested
    class User_writes {

        @Test
        void a_write_to_the_users_own_attributes_advances_that_user_alone() {
            RepresentationChange change = RepresentationChange.userWritten(ALICE, NOW);

            assertThat(change.advanced()).containsExactly(ALICE);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_replacement_that_keeps_the_member_label_advances_that_user_alone() {
            RepresentationChange change = RepresentationChange.userReplaced(
                    ALICE, "Alice", "Alice", List.of(GROUP), NOW);

            assertThat(change.advanced()).containsExactly(ALICE);
        }

        @Test
        void a_replacement_that_changes_the_member_label_advances_the_user_and_its_groups() {
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

            RepresentationChange change = RepresentationChange.userReplaced(
                    ALICE, "Alice", "Alice Anew", List.of(GROUP, other), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(ALICE, GROUP, other);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_label_change_that_only_changes_case_is_a_label_change() {
            assertThat(RepresentationChange.userReplaced(
                    ALICE, "alice", "Alice", List.of(GROUP), NOW).advanced())
                    .containsExactlyInAnyOrder(ALICE, GROUP);
        }

        @Test
        void a_label_change_of_a_user_in_no_group_advances_that_user_alone() {
            assertThat(RepresentationChange.userReplaced(
                    ALICE, "Alice", "Alice Anew", List.of(), NOW).advanced())
                    .containsExactly(ALICE);
        }

        @Test
        void a_deleted_user_advances_every_group_it_belonged_to_and_not_itself() {
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

            RepresentationChange change =
                    RepresentationChange.userDeleted(List.of(GROUP, other), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, other);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_deleted_user_in_no_group_advances_nothing() {
            assertThat(RepresentationChange.userDeleted(List.of(), NOW).advanced()).isEmpty();
        }
    }

    @Nested
    class Group_create {

        @Test
        void a_group_created_with_members_advances_every_member_but_not_the_new_group() {
            RepresentationChange change =
                    RepresentationChange.groupCreated(List.of(ALICE, BOB), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(ALICE, BOB);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_group_created_empty_advances_nothing() {
            assertThat(RepresentationChange.groupCreated(List.of(), NOW).advanced()).isEmpty();
        }
    }

    @Nested
    class Group_replace {

        @Test
        void an_identical_resend_advances_nothing_not_even_the_group() {
            RepresentationChange change = replaced("Engineering", "Engineering",
                    Set.of(ALICE, BOB), Set.of(BOB, ALICE));

            assertThat(change.advanced()).isEmpty();
        }

        @Test
        void a_membership_change_advances_the_group_and_the_users_added_and_removed_only() {
            RepresentationChange change = replaced("Engineering", "Engineering",
                    Set.of(ALICE, BOB), Set.of(BOB, CAROL));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE, CAROL);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_member_only_added_advances_the_group_and_that_member() {
            RepresentationChange change = replaced("Engineering", "Engineering",
                    Set.of(ALICE), Set.of(ALICE, BOB));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, BOB);
        }

        @Test
        void a_member_only_removed_advances_the_group_and_that_member() {
            RepresentationChange change = replaced("Engineering", "Engineering",
                    Set.of(ALICE, BOB), Set.of(ALICE));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, BOB);
        }

        /**
         * A PATCH that removes a member and adds the same member back arrives here as a
         * membership in which that member is both before and after: its {@code groups} did not
         * change, so it does not advance — while the member truly added does.
         */
        @Test
        void a_member_removed_and_re_added_in_one_write_does_not_advance() {
            RepresentationChange change = replaced("Engineering", "Engineering",
                    List.of(ALICE, BOB), List.of(ALICE, BOB, CAROL));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, CAROL)
                    .doesNotContain(ALICE, BOB);
        }

        @Test
        void a_member_removed_and_re_added_with_nothing_else_changed_advances_nothing() {
            RepresentationChange change = replaced("Engineering", "Engineering",
                    List.of(ALICE, BOB), List.of(BOB, ALICE));

            assertThat(change.advanced()).isEmpty();
        }

        @Test
        void a_rename_advances_the_group_and_every_member() {
            RepresentationChange change = replaced("Engineering", "Platform",
                    Set.of(ALICE, BOB), Set.of(ALICE, BOB));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE, BOB);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_rename_of_an_empty_group_advances_the_group_alone() {
            RepresentationChange change = replaced("Engineering", "Platform", Set.of(), Set.of());

            assertThat(change.advanced()).containsExactly(GROUP);
        }

        /** A name differing only in case is a rename: the rendered label changed. */
        @Test
        void a_rename_that_changes_only_case_is_a_rename() {
            RepresentationChange change = replaced("Engineering", "engineering",
                    Set.of(ALICE), Set.of(ALICE));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE);
        }

        @Test
        void a_rename_with_a_membership_change_advances_every_member_before_or_after() {
            RepresentationChange change = replaced("Engineering", "Platform",
                    Set.of(ALICE, BOB), Set.of(BOB, CAROL));

            assertThat(change.advanced())
                    .containsExactlyInAnyOrder(GROUP, ALICE, BOB, CAROL)
                    .doesNotContain(DAVE);
        }

        private RepresentationChange replaced(
                String previousName,
                String name,
                Collection<UUID> previousMembers,
                Collection<UUID> members) {
            return RepresentationChange.groupReplaced(
                    GROUP, previousName, name, previousMembers, members, NOW);
        }
    }

    @Nested
    class Other_group_writes {

        @Test
        void a_change_no_group_column_records_advances_the_group_alone() {
            RepresentationChange change = RepresentationChange.groupTouched(GROUP, NOW);

            assertThat(change.advanced()).containsExactly(GROUP);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_deleted_group_advances_every_former_member_and_not_itself() {
            RepresentationChange change =
                    RepresentationChange.groupDeleted(List.of(ALICE, BOB), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(ALICE, BOB);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_deleted_group_with_no_members_advances_nothing() {
            assertThat(RepresentationChange.groupDeleted(List.of(), NOW).advanced()).isEmpty();
        }

        @Test
        void a_removed_member_advances_the_group_and_that_user() {
            RepresentationChange change = RepresentationChange.memberRemoved(GROUP, ALICE, NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE);
            assertThat(change.at()).isEqualTo(NOW);
        }
    }

    @Nested
    class Advancing {

        /** A repeated id is still one resource, advanced once. */
        @Test
        void each_resource_is_named_once_in_one_statement_at_the_writes_timestamp() {
            ScimResourceJpaRepository resources = mock(ScimResourceJpaRepository.class);

            RepresentationChange.groupCreated(List.of(ALICE, BOB, ALICE), NOW)
                    .advanceIn(resources);

            verify(resources).advanceVersions(
                    argThat(ids -> Set.copyOf(ids).equals(Set.of(ALICE, BOB)) && ids.size() == 2),
                    eq(NOW));
            verifyNoMoreInteractions(resources);
        }

        /**
         * More resources than SQL Server binds in one request (2,100 parameters) go out as
         * statements of at most 2,000 ids each, together naming every resource exactly once — 4,500
         * ids are three statements: 2,000, 2,000 and 500.
         */
        @Test
        void a_change_naming_more_than_one_request_binds_is_split_into_statements_of_2000() {
            ScimResourceJpaRepository resources = mock(ScimResourceJpaRepository.class);
            List<UUID> members = IntStream.range(0, 4_500).mapToObj(i -> UUID.randomUUID()).toList();
            List<Collection<UUID>> statements = new ArrayList<>();
            when(resources.advanceVersions(anyCollection(), eq(NOW))).thenAnswer(call -> {
                Collection<UUID> ids = call.getArgument(0);
                statements.add(List.copyOf(ids));
                return ids.size();
            });

            RepresentationChange.groupDeleted(members, NOW).advanceIn(resources);

            assertThat(statements).extracting(Collection::size).containsExactly(2_000, 2_000, 500);
            assertThat(statements.stream().flatMap(Collection::stream))
                    .containsExactlyInAnyOrderElementsOf(members);
        }

        @Test
        void a_change_naming_nothing_issues_no_statement() {
            ScimResourceJpaRepository resources = mock(ScimResourceJpaRepository.class);

            RepresentationChange.groupDeleted(List.of(), NOW).advanceIn(resources);

            verifyNoInteractions(resources);
        }
    }
}
