package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupHeadcount;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What every {@link ScimGroupRepository} does, whatever stores the Groups: the SQL Server adapter,
 * and the in-memory store the Group write use cases, the dormancy job and seeding run their unit
 * tests against. A unit test that passes against the in-memory store says something about
 * production only while both satisfy this, so each runs it.
 *
 * <p>The User port over the same store, {@link #users()}, stores the members a Group write names
 * and reads back the versions it advances. Every instant is in whole seconds, so it survives the
 * {@code DATETIMEOFFSET(6)} columns unchanged.
 *
 * <p>Concurrency is out of scope, as it is for {@link ScimUserRepositoryContract}: what a Group
 * read sees while a deletion commits is {@code ScimGroupPersistenceAdapterIntegrationTests}'.
 */
public abstract class ScimGroupRepositoryContract {

    /** The Group port under test. */
    protected abstract ScimGroupRepository groups();

    /** The User port over the same store. */
    protected abstract ScimUserRepository users();

    private static final Instant EARLIER = Instant.parse("2026-03-01T08:00:00Z");

    /** The dormancy cutoff: after {@link #EARLIER}, before {@link #LATER}. */
    private static final Instant CUTOFF = Instant.parse("2026-04-01T08:00:00Z");

    private static final Instant LATER = Instant.parse("2026-05-01T08:00:00Z");

    /** When a write that advances a version is made: after everything stored here. */
    private static final Instant WRITTEN_AT = Instant.parse("2026-07-01T08:00:00Z");

    /** A resource's version and {@code meta.lastModified} as {@link ScimIdentities#user} stores them. */
    private static final Tuple UNMOVED = Tuple.tuple(ScimUser.INITIAL_VERSION, ScimIdentities.NOW);

    /** A version advanced once, by a write made at {@link #WRITTEN_AT}. */
    private static final Tuple ADVANCED = Tuple.tuple(ScimUser.INITIAL_VERSION + 1, WRITTEN_AT);

    /**
     * A member of a Group {@link ScimIdentities#group} built, as that Group's creation left it:
     * joining advanced it once, at the Group's creation time.
     */
    private static final Tuple JOINED =
            Tuple.tuple(ScimUser.INITIAL_VERSION + 1, ScimIdentities.NOW);

    /** A {@link #JOINED} member advanced once more, by a write made at {@link #WRITTEN_AT}. */
    private static final Tuple JOINED_THEN_ADVANCED =
            Tuple.tuple(ScimUser.INITIAL_VERSION + 2, WRITTEN_AT);

    // ---- create --------------------------------------------------------------------------------

    /** Each member carries its label: the User's {@code displayName}, or its {@code userName}. */
    @Test
    void a_created_group_is_read_back_with_each_member_labelled() {
        String suffix = UUID.randomUUID().toString();
        ScimUser named = users().create(
                ScimIdentities.withDisplayName(ScimIdentities.user("a-" + suffix), "Ada"));
        ScimUser unnamed = users().create(ScimIdentities.user("b-" + suffix));
        ScimGroup given = ScimIdentities.group(unique(), named, unnamed);

        groups().create(given);

        assertThat(groups().findById(given.id())).contains(new ScimGroup(given.id(),
                given.displayName(),
                List.of(new ScimGroupMember(named.id(), "Ada"),
                        new ScimGroupMember(unnamed.id(), "b-" + suffix)),
                null, ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW));
    }

    /** The order a connector sent the members in is not kept: they read back by userName. */
    @Test
    void a_groups_members_are_read_in_normalized_user_name_order() {
        String suffix = UUID.randomUUID().toString();
        ScimUser last = users().create(ScimIdentities.user("z-" + suffix));
        ScimUser first = users().create(ScimIdentities.user("A-" + suffix));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), last, first));

        assertThat(memberIdsOf(group.id())).containsExactly(first.id(), last.id());
    }

    /** The label is read from the User, never stored with the membership, so it follows the User. */
    @Test
    void a_members_label_is_the_users_label_as_it_now_stands() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), user));

        users().replace(ScimIdentities.withDisplayName(user, "Renamed"), WRITTEN_AT);

        assertThat(groups().findById(group.id()).orElseThrow().members())
                .containsExactly(new ScimGroupMember(user.id(), "Renamed"));
    }

    @Test
    void create_reports_the_group_as_stored() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup given = ScimIdentities.group(unique(), user);

        assertThat(groups().create(given)).isEqualTo(groups().findById(given.id()).orElseThrow());
    }

    /** No provisioning path can mint a Group that claims the Admin group's authority. */
    @Test
    void create_refuses_a_reservation_carried_by_the_group() {
        ScimGroup group = ScimIdentities.group(unique());
        ScimGroup claimingTheReservation = new ScimGroup(group.id(), group.displayName(),
                List.of(), ReservedResourceName.ADMIN_GROUP, group.version(), group.createdAt(),
                group.lastModifiedAt());

        groups().create(claimingTheReservation);

        assertThat(groups().findById(group.id()).orElseThrow().reservedName()).isNull();
    }

    /** Compared on the normalized form, so the case the name is typed in does not matter. */
    @Test
    void create_refuses_a_normalized_display_name_another_group_holds() {
        String name = unique();
        groups().create(ScimIdentities.group(name));

        assertThatThrownBy(() -> groups().create(ScimIdentities.group(name.toUpperCase())))
                .isInstanceOf(DuplicateDisplayNameException.class);
    }

    @Test
    void create_refuses_a_member_naming_nobody() {
        ScimGroup naming = new ScimGroup(UUID.randomUUID(), unique(),
                List.of(ScimGroupMember.reference(UUID.randomUUID())), null,
                ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW);

        assertThatThrownBy(() -> groups().create(naming))
                .isInstanceOf(UnknownGroupMemberException.class);
    }

    /** A Group is not a User, so it cannot be a member: nesting has nowhere to be written. */
    @Test
    void create_refuses_a_member_naming_a_group() {
        ScimGroup other = groups().create(ScimIdentities.group(unique()));
        ScimGroup naming = new ScimGroup(UUID.randomUUID(), unique(),
                List.of(ScimGroupMember.reference(other.id())), null,
                ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW);

        assertThatThrownBy(() -> groups().create(naming))
                .isInstanceOf(UnknownGroupMemberException.class);
    }

    /** Each member's rendered {@code groups} gained the new Group. */
    @Test
    void creating_a_group_advances_each_member() {
        ScimUser user = users().create(ScimIdentities.user(unique()));

        groups().create(groupAt(WRITTEN_AT, user));

        assertThat(userVersionOf(user.id())).isEqualTo(ADVANCED);
    }

    /** The new Group's own version is the initial one by definition. */
    @Test
    void creating_a_group_with_members_leaves_its_own_version_initial() {
        ScimUser user = users().create(ScimIdentities.user(unique()));

        ScimGroup created = groups().create(groupAt(WRITTEN_AT, user));

        assertThat(groupVersionOf(created.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION, WRITTEN_AT));
    }

    // ---- createReserved ------------------------------------------------------------------------

    @Test
    void create_reserved_applies_the_reservation() {
        releaseTheAdminGroupReservation();
        ScimGroup group = ScimIdentities.group(unique());

        groups().createReserved(group, ReservedResourceName.ADMIN_GROUP);

        assertThat(groups().findById(group.id()).orElseThrow().reservedName())
                .isEqualTo(ReservedResourceName.ADMIN_GROUP);
    }

    /**
     * A reservation is held once. A second claim is the store's integrity violation, not a taken
     * {@code displayName}: seeding looks the reservation up first, so reaching this is a fault.
     */
    @Test
    void create_reserved_refuses_a_reservation_already_held() {
        releaseTheAdminGroupReservation();
        groups().createReserved(ScimIdentities.group(unique()), ReservedResourceName.ADMIN_GROUP);
        ScimGroup second = ScimIdentities.group(unique());

        assertThatThrownBy(() -> groups().createReserved(second, ReservedResourceName.ADMIN_GROUP))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateDisplayNameException.class);
    }

    @Test
    void create_reserved_refuses_a_normalized_display_name_another_group_holds() {
        releaseTheAdminGroupReservation();
        String name = unique();
        groups().create(ScimIdentities.group(name));

        assertThatThrownBy(() -> groups().createReserved(
                        ScimIdentities.group(name.toUpperCase()), ReservedResourceName.ADMIN_GROUP))
                .isInstanceOf(DuplicateDisplayNameException.class);
    }

    // ---- lookups -------------------------------------------------------------------------------

    @Test
    void a_missing_group_is_not_found_by_id() {
        assertThat(groups().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void a_users_id_is_not_found_as_a_group() {
        ScimUser user = users().create(ScimIdentities.user(unique()));

        assertThat(groups().findById(user.id())).isEmpty();
    }

    @Test
    void the_lookup_by_reserved_name_finds_the_group_holding_it() {
        releaseTheAdminGroupReservation();
        groups().create(ScimIdentities.group(unique()));
        ScimGroup holder = groups().createReserved(
                ScimIdentities.group(unique()), ReservedResourceName.ADMIN_GROUP);

        assertThat(groups().findByReservedName(ReservedResourceName.ADMIN_GROUP)
                .map(ScimGroup::id)).contains(holder.id());
    }

    @Test
    void the_lookup_by_a_reserved_name_nobody_holds_finds_nothing() {
        releaseTheAdminGroupReservation();

        assertThat(groups().findByReservedName(ReservedResourceName.ADMIN_GROUP)).isEmpty();
    }

    @Test
    void groups_by_id_come_in_the_order_asked_skipping_an_id_naming_no_group() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup first = groups().create(ScimIdentities.group(unique()));
        ScimGroup second = groups().create(ScimIdentities.group(unique()));

        assertThat(groups().findAllById(
                        List.of(second.id(), UUID.randomUUID(), user.id(), first.id()))
                .stream().map(ScimGroup::id)).containsExactly(second.id(), first.id());
    }

    @Test
    void groups_by_id_carry_their_memberships() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), user));

        assertThat(groups().findAllById(List.of(group.id()))).containsExactly(group);
    }

    @Test
    void groups_by_no_id_are_none() {
        assertThat(groups().findAllById(List.of())).isEmpty();
    }

    /** Asked only about the Groups this case stored: a shared store may hold others. */
    @Test
    void every_group_is_listed_in_normalized_display_name_order() {
        String suffix = UUID.randomUUID().toString();
        ScimGroup second = groups().create(ScimIdentities.group("b-" + suffix));
        ScimGroup first = groups().create(ScimIdentities.group("A-" + suffix));
        Set<UUID> mine = Set.of(first.id(), second.id());

        List<UUID> listed = groups().findAllOrderedByNormalizedDisplayName().stream()
                .map(ScimGroup::id)
                .filter(mine::contains)
                .toList();

        assertThat(listed).containsExactly(first.id(), second.id());
    }

    @Test
    void the_listing_carries_each_groups_memberships() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), user));

        assertThat(groups().findAllOrderedByNormalizedDisplayName()).contains(group);
    }

    /**
     * The Groups projection's listing: each Group's name, reservation and how many direct members
     * it has, counted rather than read, in normalized {@code displayName} order.
     */
    @Test
    void every_group_is_listed_with_its_direct_member_count_in_normalized_display_name_order() {
        String suffix = UUID.randomUUID().toString();
        ScimUser ada = users().create(ScimIdentities.user("ada-" + suffix));
        ScimUser bea = users().create(ScimIdentities.user("bea-" + suffix));
        ScimUser cy = users().create(ScimIdentities.user("cy-" + suffix));
        ScimGroup second = groups().create(ScimIdentities.group("b-" + suffix, ada, bea, cy));
        ScimGroup first = groups().create(ScimIdentities.group("A-" + suffix, bea));
        ScimGroup empty = groups().create(ScimIdentities.group("c-" + suffix));
        Set<UUID> mine = Set.of(first.id(), second.id(), empty.id());

        List<ScimGroupHeadcount> listed =
                groups().findAllHeadcountsOrderedByNormalizedDisplayName().stream()
                        .filter(group -> mine.contains(group.id()))
                        .toList();

        assertThat(listed).containsExactly(
                new ScimGroupHeadcount(first.id(), "A-" + suffix, null, 1),
                new ScimGroupHeadcount(second.id(), "b-" + suffix, null, 3),
                new ScimGroupHeadcount(empty.id(), "c-" + suffix, null, 0));
    }

    /** The Admin group is told apart by its reservation, in the listing as anywhere. */
    @Test
    void the_counted_listing_carries_the_reservation() {
        releaseTheAdminGroupReservation();
        ScimGroup reserved = groups().createReserved(
                ScimIdentities.group(unique()), ReservedResourceName.ADMIN_GROUP);

        assertThat(groups().findAllHeadcountsOrderedByNormalizedDisplayName())
                .filteredOn(group -> group.id().equals(reserved.id()))
                .extracting(ScimGroupHeadcount::reservedName)
                .containsExactly(ReservedResourceName.ADMIN_GROUP);
    }

    @Test
    void a_stored_group_exists_and_an_id_naming_no_group_does_not() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        assertThat(List.of(groups().existsById(group.id()), groups().existsById(user.id()),
                        groups().existsById(UUID.randomUUID())))
                .containsExactly(true, false, false);
    }

    @Test
    void a_deleted_group_no_longer_exists() {
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        groups().deleteById(group.id(), WRITTEN_AT);

        assertThat(groups().existsById(group.id())).isFalse();
    }

    // ---- replace -------------------------------------------------------------------------------

    @Test
    void a_replacement_stores_the_name_and_the_whole_membership() {
        ScimUser kept = users().create(ScimIdentities.user(unique()));
        ScimUser removed = users().create(ScimIdentities.user(unique()));
        ScimUser added = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), kept, removed));
        String name = unique();

        groups().replace(group.replacedWith(name, members(kept, added), WRITTEN_AT));

        ScimGroup read = groups().findById(group.id()).orElseThrow();
        assertThat(Tuple.tuple(read.displayName(), Set.copyOf(memberIdsOf(group.id()))))
                .isEqualTo(Tuple.tuple(name, Set.of(kept.id(), added.id())));
    }

    @Test
    void a_replacement_reports_the_group_as_it_now_stands() {
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique()));
        String name = unique();

        assertThat(groups().replace(group.replacedWith(name, members(user), WRITTEN_AT)))
                .contains(new ScimGroup(group.id(), name,
                        List.of(new ScimGroupMember(user.id(), user.profile().userName())), null,
                        ScimUser.INITIAL_VERSION + 1, ScimIdentities.NOW, WRITTEN_AT));
    }

    /** A write cannot reserve or un-reserve a Group, nor move when it was created. */
    @Test
    void a_replacement_keeps_the_reservation_and_the_creation_time() {
        releaseTheAdminGroupReservation();
        ScimGroup group = groups().createReserved(
                ScimIdentities.group(unique()), ReservedResourceName.ADMIN_GROUP);

        groups().replace(new ScimGroup(group.id(), unique(), List.of(), null, group.version(),
                LATER, WRITTEN_AT));

        ScimGroup read = groups().findById(group.id()).orElseThrow();
        assertThat(Tuple.tuple(read.reservedName(), read.createdAt()))
                .isEqualTo(Tuple.tuple(ReservedResourceName.ADMIN_GROUP, ScimIdentities.NOW));
    }

    @Test
    void a_replacement_of_a_missing_group_reports_absence() {
        assertThat(groups().replace(ScimIdentities.group(unique()))).isEmpty();
    }

    @Test
    void a_replacement_to_a_normalized_display_name_another_group_holds_is_refused() {
        ScimGroup holder = groups().create(ScimIdentities.group(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        assertThatThrownBy(() -> groups().replace(group.replacedWith(
                        holder.displayName().toUpperCase(), List.of(), WRITTEN_AT)))
                .isInstanceOf(DuplicateDisplayNameException.class);
    }

    @Test
    void a_replacement_naming_a_member_who_is_nobody_is_refused() {
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        assertThatThrownBy(() -> groups().replace(group.replacedWith(group.displayName(),
                        List.of(ScimGroupMember.reference(UUID.randomUUID())), WRITTEN_AT)))
                .isInstanceOf(UnknownGroupMemberException.class);
    }

    /** An identical re-send changed nothing anyone renders, so not even the Group moves. */
    @Test
    void a_replacement_changing_nothing_advances_nothing() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member));

        groups().replace(group.replacedWith(group.displayName(), members(member), WRITTEN_AT));

        assertThat(List.of(groupVersionOf(group.id()), userVersionOf(member.id())))
                .containsExactly(UNMOVED, JOINED);
    }

    /** The Group, and the Users whose membership started or stopped; a kept member is unchanged. */
    @Test
    void a_membership_change_advances_the_group_and_the_users_added_or_removed() {
        ScimUser kept = users().create(ScimIdentities.user(unique()));
        ScimUser removed = users().create(ScimIdentities.user(unique()));
        ScimUser added = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), kept, removed));

        groups().replace(group.replacedWith(group.displayName(), members(kept, added), WRITTEN_AT));

        assertThat(List.of(groupVersionOf(group.id()), userVersionOf(kept.id()),
                        userVersionOf(removed.id()), userVersionOf(added.id())))
                .containsExactly(ADVANCED, JOINED, JOINED_THEN_ADVANCED, ADVANCED);
    }

    /** The label inside every member's {@code groups} changed, so every member moves. */
    @Test
    void a_rename_advances_the_group_and_every_member() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimUser other = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member, other));

        groups().replace(group.replacedWith(unique(), members(member, other), WRITTEN_AT));

        assertThat(List.of(groupVersionOf(group.id()), userVersionOf(member.id()),
                        userVersionOf(other.id())))
                .containsExactly(ADVANCED, JOINED_THEN_ADVANCED, JOINED_THEN_ADVANCED);
    }

    /** A change of case alone is a rename: the label is rendered as submitted. */
    @Test
    void a_rename_changing_only_the_case_advances_the_group() {
        ScimGroup group = groups().create(ScimIdentities.group("contract-" + UUID.randomUUID()));

        groups().replace(group.replacedWith(group.displayName().toUpperCase(), List.of(),
                WRITTEN_AT));

        assertThat(groupVersionOf(group.id())).isEqualTo(ADVANCED);
    }

    // ---- advanceVersion ------------------------------------------------------------------------

    @Test
    void advancing_the_version_moves_it_once_and_records_when() {
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        groups().advanceVersion(group.id(), WRITTEN_AT);

        assertThat(groupVersionOf(group.id())).isEqualTo(ADVANCED);
    }

    /** An alias is not rendered in any member's {@code groups}. */
    @Test
    void advancing_the_version_leaves_the_members() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member));

        groups().advanceVersion(group.id(), WRITTEN_AT);

        assertThat(userVersionOf(member.id())).isEqualTo(JOINED);
    }

    @Test
    void advancing_the_version_reports_the_group_as_it_now_stands() {
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        assertThat(groups().advanceVersion(group.id(), WRITTEN_AT)).contains(new ScimGroup(
                group.id(), group.displayName(), List.of(), null, ScimUser.INITIAL_VERSION + 1,
                ScimIdentities.NOW, WRITTEN_AT));
    }

    @Test
    void advancing_the_version_of_a_missing_group_reports_absence() {
        assertThat(groups().advanceVersion(UUID.randomUUID(), WRITTEN_AT)).isEmpty();
    }

    // ---- deleteById ----------------------------------------------------------------------------

    @Test
    void a_deleted_group_is_gone() {
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        groups().deleteById(group.id(), WRITTEN_AT);

        assertThat(groups().findById(group.id())).isEmpty();
    }

    @Test
    void deleting_a_group_reports_that_one_was_there() {
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        assertThat(groups().deleteById(group.id(), WRITTEN_AT)).isTrue();
    }

    @Test
    void deleting_a_missing_group_reports_nothing_was_there() {
        assertThat(groups().deleteById(UUID.randomUUID(), WRITTEN_AT)).isFalse();
    }

    @Test
    void a_deleted_group_leaves_no_membership() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member));

        groups().deleteById(group.id(), WRITTEN_AT);

        assertThat(groups().findGroupsOfUser(member.id())).isEmpty();
    }

    /** Each former member's {@code groups} lost an entry. */
    @Test
    void deleting_a_group_advances_each_former_member() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member));

        groups().deleteById(group.id(), WRITTEN_AT);

        assertThat(userVersionOf(member.id())).isEqualTo(JOINED_THEN_ADVANCED);
    }

    /** A hard delete: the name is free for the next Group at once. */
    @Test
    void a_deleted_groups_display_name_is_free_again() {
        String name = unique();
        ScimGroup group = groups().create(ScimIdentities.group(name));
        groups().deleteById(group.id(), WRITTEN_AT);

        ScimGroup successor = groups().create(ScimIdentities.group(name));

        assertThat(groups().findById(successor.id()).map(ScimGroup::displayName)).contains(name);
    }

    // ---- removeMember --------------------------------------------------------------------------

    @Test
    void removing_a_member_leaves_every_other_member() {
        ScimUser removed = users().create(ScimIdentities.user(unique()));
        ScimUser kept = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), removed, kept));

        groups().removeMember(group.id(), removed.id(), WRITTEN_AT);

        assertThat(memberIdsOf(group.id())).containsExactly(kept.id());
    }

    @Test
    void removing_a_member_leaves_the_users_other_groups() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member));
        ScimGroup other = groups().create(ScimIdentities.group(unique(), member));

        groups().removeMember(group.id(), member.id(), WRITTEN_AT);

        assertThat(groups().findGroupsOfUser(member.id()))
                .containsExactly(new ScimGroupReference(other.id(), other.displayName()));
    }

    @Test
    void removing_a_member_reports_that_one_was_there() {
        ScimUser member = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), member));

        assertThat(groups().removeMember(group.id(), member.id(), WRITTEN_AT)).isTrue();
    }

    /** The Group's {@code members} and the User's {@code groups} each lost an entry; no one else's. */
    @Test
    void removing_a_member_advances_the_group_and_that_user_alone() {
        ScimUser removed = users().create(ScimIdentities.user(unique()));
        ScimUser kept = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique(), removed, kept));

        groups().removeMember(group.id(), removed.id(), WRITTEN_AT);

        assertThat(List.of(groupVersionOf(group.id()), userVersionOf(removed.id()),
                        userVersionOf(kept.id())))
                .containsExactly(ADVANCED, JOINED_THEN_ADVANCED, JOINED);
    }

    @Test
    void removing_a_user_who_is_not_a_member_reports_nothing_was_there() {
        ScimUser outsider = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        assertThat(groups().removeMember(group.id(), outsider.id(), WRITTEN_AT)).isFalse();
    }

    @Test
    void removing_a_user_who_is_not_a_member_advances_nothing() {
        ScimUser outsider = users().create(ScimIdentities.user(unique()));
        ScimGroup group = groups().create(ScimIdentities.group(unique()));

        groups().removeMember(group.id(), outsider.id(), WRITTEN_AT);

        assertThat(List.of(groupVersionOf(group.id()), userVersionOf(outsider.id())))
                .containsExactly(UNMOVED, UNMOVED);
    }

    // ---- findGroupsOfUser ----------------------------------------------------------------------

    /** References, in normalized {@code displayName} order, and only the User's own Groups. */
    @Test
    void a_users_groups_are_its_direct_groups_in_normalized_display_name_order() {
        String suffix = UUID.randomUUID().toString();
        ScimUser user = users().create(ScimIdentities.user(unique()));
        ScimUser other = users().create(ScimIdentities.user(unique()));
        ScimGroup second = groups().create(ScimIdentities.group("b-" + suffix, user));
        ScimGroup first = groups().create(ScimIdentities.group("A-" + suffix, user, other));
        groups().create(ScimIdentities.group(unique(), other));

        assertThat(groups().findGroupsOfUser(user.id())).containsExactly(
                new ScimGroupReference(first.id(), first.displayName()),
                new ScimGroupReference(second.id(), second.displayName()));
    }

    // ---- findDormantMemberships ----------------------------------------------------------------

    /**
     * Exactly the memberships of unreserved Users whose dormancy basis — the last authentication,
     * or the creation when there has been none — is strictly before the cutoff, locked or active
     * or not. Read for this case's Group alone, since a shared store holds other cases' too.
     */
    @Test
    void the_dormant_memberships_are_exactly_those_of_unreserved_users_dormant_before_the_cutoff() {
        ScimUser dormant = stored(new ScimLoginState("hash", 0, null, null, EARLIER, null));
        ScimUser neverAuthenticated = stored(ScimLoginState.of("hash"));
        ScimUser locked = stored(new ScimLoginState(
                "hash", 0, EARLIER, LockCause.DORMANCY, EARLIER, null));
        ScimUser inactive = users().create(ScimIdentities.withActive(
                ScimIdentities.inactiveUser(unique()), false,
                new ScimLoginState("hash", 0, null, null, EARLIER, null)));
        ScimUser recent = stored(new ScimLoginState("hash", 0, null, null, LATER, null));
        ScimUser atTheCutoff = stored(new ScimLoginState("hash", 0, null, null, CUTOFF, null));
        ScimUser createdRecently = users().create(new ScimUser(UUID.randomUUID(),
                ScimIdentities.profile(unique(), true), ScimLoginState.of("hash"), null,
                ScimUser.INITIAL_VERSION, LATER, LATER));
        ScimUser reserved = reservedDormantUser();
        ScimGroup group = groups().create(ScimIdentities.group(unique(), dormant,
                neverAuthenticated, locked, inactive, recent, atTheCutoff, createdRecently,
                reserved));

        assertThat(groups().findDormantMemberships(CUTOFF))
                .filteredOn(membership -> membership.groupId().equals(group.id()))
                .containsExactlyInAnyOrder(
                        new ScimGroupMembership(dormant.id(), group.id()),
                        new ScimGroupMembership(neverAuthenticated.id(), group.id()),
                        new ScimGroupMembership(locked.id(), group.id()),
                        new ScimGroupMembership(inactive.id(), group.id()));
    }

    /** Every Group a dormant User belongs to, whatever the Group confers. */
    @Test
    void the_dormant_memberships_are_of_every_group_the_user_belongs_to() {
        ScimUser dormant = stored(new ScimLoginState("hash", 0, null, null, EARLIER, null));
        ScimGroup first = groups().create(ScimIdentities.group(unique(), dormant));
        ScimGroup second = groups().create(ScimIdentities.group(unique(), dormant));

        assertThat(groups().findDormantMemberships(CUTOFF))
                .filteredOn(membership -> membership.userId().equals(dormant.id()))
                .containsExactlyInAnyOrder(new ScimGroupMembership(dormant.id(), first.id()),
                        new ScimGroupMembership(dormant.id(), second.id()));
    }

    /**
     * By User id and then Group id, in SQL Server's {@code uniqueidentifier} order: the last group
     * of the text first, and the first group from its last byte. The ids share all but their first
     * group, {@code 7fffff00} and {@code 800000ff}, so the engine puts {@code 7fffff00} first where
     * {@link UUID#compareTo}, comparing signed halves, puts {@code 800000ff} first.
     */
    @Test
    void the_dormant_memberships_are_ordered_by_user_id_then_group_id_as_sql_server_orders_ids() {
        String userTail = UUID.randomUUID().toString().substring(8);
        String groupTail = UUID.randomUUID().toString().substring(8);
        ScimUser engineFirst = dormantUser(UUID.fromString("7fffff00" + userTail));
        ScimUser engineSecond = dormantUser(UUID.fromString("800000ff" + userTail));
        ScimGroup groupSecond = groups().create(groupWithId(
                UUID.fromString("800000ff" + groupTail), engineSecond, engineFirst));
        ScimGroup groupFirst = groups().create(groupWithId(
                UUID.fromString("7fffff00" + groupTail), engineSecond, engineFirst));

        assertThat(groups().findDormantMemberships(CUTOFF))
                .filteredOn(membership -> membership.groupId().equals(groupFirst.id())
                        || membership.groupId().equals(groupSecond.id()))
                .containsExactly(
                        new ScimGroupMembership(engineFirst.id(), groupFirst.id()),
                        new ScimGroupMembership(engineFirst.id(), groupSecond.id()),
                        new ScimGroupMembership(engineSecond.id(), groupFirst.id()),
                        new ScimGroupMembership(engineSecond.id(), groupSecond.id()));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Frees the Admin group's reservation by deleting whichever Group holds it, for a case that
     * claims it: the reservation is held once per store. A store shared with other tests must
     * therefore undo each case's writes afterwards.
     */
    private void releaseTheAdminGroupReservation() {
        groups().findByReservedName(ReservedResourceName.ADMIN_GROUP)
                .ifPresent(holder -> groups().deleteById(holder.id(), WRITTEN_AT));
    }

    /**
     * The Bootstrap Admin, dormant since {@link #EARLIER}. Whoever holds the reservation is deleted
     * first, as {@link #releaseTheAdminGroupReservation} frees the Group's.
     */
    private ScimUser reservedDormantUser() {
        ScimIdentities.releaseReservation(
                users(), ReservedResourceName.BOOTSTRAP_ADMIN, WRITTEN_AT);
        return users().createReserved(
                ScimIdentities.userWithLoginState(unique(),
                        new ScimLoginState("hash", 0, null, null, EARLIER, null)),
                ReservedResourceName.BOOTSTRAP_ADMIN);
    }

    /** An unreserved, active User created at {@link ScimIdentities#NOW} with this login state. */
    private ScimUser stored(ScimLoginState login) {
        return users().create(ScimIdentities.userWithLoginState(unique(), login));
    }

    /** An unreserved User under this id, last authenticated at {@link #EARLIER}. */
    private ScimUser dormantUser(UUID id) {
        return users().create(new ScimUser(id, ScimIdentities.profile(unique(), true),
                new ScimLoginState("hash", 0, null, null, EARLIER, null), null,
                ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW));
    }

    /** A new Group of these Users, created at {@code at}. */
    private static ScimGroup groupAt(Instant at, ScimUser... members) {
        return ScimGroup.created(UUID.randomUUID(), unique(), members(members), at);
    }

    /** A new Group of these Users under this id. */
    private static ScimGroup groupWithId(UUID id, ScimUser... members) {
        return ScimGroup.created(id, unique(), members(members), ScimIdentities.NOW);
    }

    private static List<ScimGroupMember> members(ScimUser... users) {
        return Arrays.stream(users).map(user -> ScimGroupMember.reference(user.id())).toList();
    }

    private List<UUID> memberIdsOf(UUID groupId) {
        return groups().findById(groupId).orElseThrow().members().stream()
                .map(ScimGroupMember::userId)
                .toList();
    }

    /** The Group's version and {@code meta.lastModified}. */
    private Tuple groupVersionOf(UUID groupId) {
        ScimGroup read = groups().findById(groupId).orElseThrow();
        return Tuple.tuple(read.version(), read.lastModifiedAt());
    }

    /** The User's version and {@code meta.lastModified}. */
    private Tuple userVersionOf(UUID userId) {
        ScimUser read = users().findById(userId).orElseThrow();
        return Tuple.tuple(read.version(), read.lastModifiedAt());
    }

    private static String unique() {
        return "contract-" + UUID.randomUUID();
    }
}
