package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class ResourceVersionAdvanceTests {

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
            ResourceVersionAdvance change = ResourceVersionAdvance.userWritten(ALICE, NOW);

            assertThat(change.advanced()).containsExactly(ALICE);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_replacement_that_keeps_the_member_label_advances_that_user_alone() {
            ResourceVersionAdvance change = ResourceVersionAdvance.userReplaced(
                    ALICE, "Alice", "Alice", List.of(GROUP), NOW);

            assertThat(change.advanced()).containsExactly(ALICE);
        }

        @Test
        void a_replacement_that_changes_the_member_label_advances_the_user_and_its_groups() {
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

            ResourceVersionAdvance change = ResourceVersionAdvance.userReplaced(
                    ALICE, "Alice", "Alice Anew", List.of(GROUP, other), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(ALICE, GROUP, other);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_label_change_that_only_changes_case_is_a_label_change() {
            assertThat(ResourceVersionAdvance.userReplaced(
                    ALICE, "alice", "Alice", List.of(GROUP), NOW).advanced())
                    .containsExactlyInAnyOrder(ALICE, GROUP);
        }

        @Test
        void a_label_change_of_a_user_in_no_group_advances_that_user_alone() {
            assertThat(ResourceVersionAdvance.userReplaced(
                    ALICE, "Alice", "Alice Anew", List.of(), NOW).advanced())
                    .containsExactly(ALICE);
        }

        @Test
        void a_deleted_user_advances_every_group_it_belonged_to_and_not_itself() {
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

            ResourceVersionAdvance change =
                    ResourceVersionAdvance.userDeleted(List.of(GROUP, other), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, other);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_deleted_user_in_no_group_advances_nothing() {
            assertThat(ResourceVersionAdvance.userDeleted(List.of(), NOW).advanced()).isEmpty();
        }
    }

    @Nested
    class Group_create {

        @Test
        void a_group_created_with_members_advances_every_member_but_not_the_new_group() {
            ResourceVersionAdvance change =
                    ResourceVersionAdvance.groupCreated(List.of(ALICE, BOB), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(ALICE, BOB);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_group_created_empty_advances_nothing() {
            assertThat(ResourceVersionAdvance.groupCreated(List.of(), NOW).advanced()).isEmpty();
        }
    }

    @Nested
    class Group_replace {

        @Test
        void an_identical_resend_advances_nothing_not_even_the_group() {
            ResourceVersionAdvance change = replaced("Engineering", "Engineering",
                    Set.of(ALICE, BOB), Set.of(BOB, ALICE));

            assertThat(change.advanced()).isEmpty();
        }

        @Test
        void a_membership_change_advances_the_group_and_the_users_added_and_removed_only() {
            ResourceVersionAdvance change = replaced("Engineering", "Engineering",
                    Set.of(ALICE, BOB), Set.of(BOB, CAROL));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE, CAROL);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_member_only_added_advances_the_group_and_that_member() {
            ResourceVersionAdvance change = replaced("Engineering", "Engineering",
                    Set.of(ALICE), Set.of(ALICE, BOB));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, BOB);
        }

        @Test
        void a_member_only_removed_advances_the_group_and_that_member() {
            ResourceVersionAdvance change = replaced("Engineering", "Engineering",
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
            ResourceVersionAdvance change = replaced("Engineering", "Engineering",
                    List.of(ALICE, BOB), List.of(ALICE, BOB, CAROL));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, CAROL)
                    .doesNotContain(ALICE, BOB);
        }

        @Test
        void a_member_removed_and_re_added_with_nothing_else_changed_advances_nothing() {
            ResourceVersionAdvance change = replaced("Engineering", "Engineering",
                    List.of(ALICE, BOB), List.of(BOB, ALICE));

            assertThat(change.advanced()).isEmpty();
        }

        @Test
        void a_rename_advances_the_group_and_every_member() {
            ResourceVersionAdvance change = replaced("Engineering", "Platform",
                    Set.of(ALICE, BOB), Set.of(ALICE, BOB));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE, BOB);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_rename_of_an_empty_group_advances_the_group_alone() {
            ResourceVersionAdvance change = replaced("Engineering", "Platform", Set.of(), Set.of());

            assertThat(change.advanced()).containsExactly(GROUP);
        }

        /** A name differing only in case is a rename: the rendered label changed. */
        @Test
        void a_rename_that_changes_only_case_is_a_rename() {
            ResourceVersionAdvance change = replaced("Engineering", "engineering",
                    Set.of(ALICE), Set.of(ALICE));

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE);
        }

        @Test
        void a_rename_with_a_membership_change_advances_every_member_before_or_after() {
            ResourceVersionAdvance change = replaced("Engineering", "Platform",
                    Set.of(ALICE, BOB), Set.of(BOB, CAROL));

            assertThat(change.advanced())
                    .containsExactlyInAnyOrder(GROUP, ALICE, BOB, CAROL)
                    .doesNotContain(DAVE);
        }

        private ResourceVersionAdvance replaced(
                String previousName,
                String name,
                Collection<UUID> previousMembers,
                Collection<UUID> members) {
            return ResourceVersionAdvance.groupReplaced(
                    GROUP, previousName, name, previousMembers, members, NOW);
        }
    }

    @Nested
    class Other_group_writes {

        @Test
        void a_change_no_group_column_records_advances_the_group_alone() {
            ResourceVersionAdvance change = ResourceVersionAdvance.groupTouched(GROUP, NOW);

            assertThat(change.advanced()).containsExactly(GROUP);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_deleted_group_advances_every_former_member_and_not_itself() {
            ResourceVersionAdvance change =
                    ResourceVersionAdvance.groupDeleted(List.of(ALICE, BOB), NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(ALICE, BOB);
            assertThat(change.at()).isEqualTo(NOW);
        }

        @Test
        void a_deleted_group_with_no_members_advances_nothing() {
            assertThat(ResourceVersionAdvance.groupDeleted(List.of(), NOW).advanced()).isEmpty();
        }

        @Test
        void a_removed_member_advances_the_group_and_that_user() {
            ResourceVersionAdvance change = ResourceVersionAdvance.memberRemoved(GROUP, ALICE, NOW);

            assertThat(change.advanced()).containsExactlyInAnyOrder(GROUP, ALICE);
            assertThat(change.at()).isEqualTo(NOW);
        }
    }
}
