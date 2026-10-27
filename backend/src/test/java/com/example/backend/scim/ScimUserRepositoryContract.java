package com.example.backend.scim;

import static com.example.backend.ScimTestRows.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What every {@link ScimUserRepository} does, whatever stores the Users: the SQL Server adapter,
 * and the in-memory store the SCIM and login use cases' unit tests run against. A unit test that
 * passes against the in-memory store says something about production only while both satisfy
 * this, so each runs it.
 *
 * <p>The Group port over the same store, {@link #groups()}, arranges and reads back the
 * memberships a User write advances or a deletion removes. Every instant is in whole seconds, so it
 * survives the {@code DATETIMEOFFSET(6)} columns unchanged.
 *
 * <p>Concurrency is out of scope, as it is for {@link ScimLoginStateRepositoryContract}.
 */
public abstract class ScimUserRepositoryContract {

    /** The directory port under test. */
    protected abstract ScimUserRepository users();

    /** The Group port over the same store. */
    protected abstract ScimGroupRepository groups();

    private static final Instant EARLIER = Instant.parse("2026-03-01T08:00:00Z");

    private static final Instant LATER = Instant.parse("2026-05-01T08:00:00Z");

    /** When a write that advances a version is made: after everything stored here. */
    private static final Instant WRITTEN_AT = Instant.parse("2026-07-01T08:00:00Z");

    // ---- create and createReserved -------------------------------------------------------------

    @Test
    void a_created_user_is_read_back_as_given() {
        ScimUser given = fullyDescribed(unique("contract"));

        users().create(given);

        assertThat(users().findById(given.id())).contains(given);
    }

    /** No provisioning path can mint a resource that protects itself from being changed. */
    @Test
    void create_refuses_a_reservation_carried_by_the_user() {
        ScimUser user = ScimIdentities.user(unique("contract"));
        ScimUser claimingTheReservation = new ScimUser(user.id(), user.profile(), user.login(),
                ReservedResourceName.BOOTSTRAP_ADMIN, user.version(), user.createdAt(),
                user.lastModifiedAt());

        users().create(claimingTheReservation);

        assertThat(users().findById(user.id()).orElseThrow().reservedName()).isNull();
    }

    @Test
    void create_refuses_a_normalized_user_name_another_user_holds() {
        String name = unique("contract");
        users().create(ScimIdentities.user(name));

        assertThatThrownBy(() -> users().create(ScimIdentities.user(name.toUpperCase())))
                .isInstanceOf(DuplicateUserNameException.class);
    }

    @Test
    void create_reserved_applies_the_reservation() {
        releaseTheBootstrapAdminReservation();
        ScimUser user = ScimIdentities.user(unique("contract"));

        users().createReserved(user, ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThat(users().findById(user.id()).orElseThrow().reservedName())
                .isEqualTo(ReservedResourceName.BOOTSTRAP_ADMIN);
    }

    /**
     * A reservation is held once. A second claim is the store's integrity violation, not a taken
     * {@code userName}: seeding looks the reservation up first, so reaching this is a fault.
     */
    @Test
    void create_reserved_refuses_a_reservation_already_held() {
        releaseTheBootstrapAdminReservation();
        users().createReserved(ScimIdentities.user(unique("contract")), ReservedResourceName.BOOTSTRAP_ADMIN);
        ScimUser second = ScimIdentities.user(unique("contract"));

        assertThatThrownBy(() -> users().createReserved(second,
                ReservedResourceName.BOOTSTRAP_ADMIN))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- lookups -------------------------------------------------------------------------------

    @Test
    void a_missing_user_is_not_found_by_id() {
        assertThat(users().findById(UUID.randomUUID())).isEmpty();
    }

    /** Compared on the normalized form, so the case the name is typed in does not matter. */
    @Test
    void the_lookup_by_normalized_user_name_finds_the_user_holding_it() {
        String name = unique("contract");
        users().create(ScimIdentities.user("other-" + name));
        ScimUser holder = users().create(ScimIdentities.user("Mixed-" + name));

        assertThat(users().findByNormalizedUserName(NormalizedUserName.of("MIXED-" + name))
                .map(ScimUser::id)).contains(holder.id());
    }

    @Test
    void the_lookup_by_a_normalized_user_name_nobody_holds_finds_nothing() {
        users().create(ScimIdentities.user(unique("contract")));

        assertThat(users().findByNormalizedUserName(NormalizedUserName.of(unique("contract")))).isEmpty();
    }

    @Test
    void the_lookup_by_reserved_name_finds_the_user_holding_it() {
        releaseTheBootstrapAdminReservation();
        users().create(ScimIdentities.user(unique("contract")));
        ScimUser holder = users().createReserved(
                ScimIdentities.user(unique("contract")), ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThat(users().findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)
                .map(ScimUser::id)).contains(holder.id());
    }

    @Test
    void the_lookup_by_a_reserved_name_nobody_holds_finds_nothing() {
        releaseTheBootstrapAdminReservation();

        assertThat(users().findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)).isEmpty();
    }

    @Test
    void users_by_id_come_in_the_order_asked_skipping_an_id_naming_nobody() {
        ScimUser first = users().create(ScimIdentities.user(unique("contract")));
        ScimUser second = users().create(ScimIdentities.user(unique("contract")));

        assertThat(users().findAllById(List.of(second.id(), UUID.randomUUID(), first.id()))
                .stream().map(ScimUser::id)).containsExactly(second.id(), first.id());
    }

    @Test
    void users_by_no_id_are_none() {
        assertThat(users().findAllById(List.of())).isEmpty();
    }

    @Test
    void every_users_id_is_listed() {
        ScimUser first = users().create(ScimIdentities.user(unique("contract")));
        ScimUser second = users().create(ScimIdentities.user(unique("contract")));

        assertThat(users().findAllIds()).contains(first.id(), second.id());
    }

    // ---- replace -------------------------------------------------------------------------------

    @Test
    void a_replacement_stores_the_given_profile() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimUser replacement = fullyDescribed(user.profile().userName(), user);

        users().replace(replacement, WRITTEN_AT);

        assertThat(users().findById(user.id()).orElseThrow().profile())
                .isEqualTo(replacement.profile());
    }

    /** The failure run and the lock are the login path's; a replacement never stores them. */
    @Test
    void a_replacement_keeps_the_stored_failure_run_and_lock() {
        ScimUser user = users().create(ScimIdentities.userWithLoginState(unique("contract"),
                new ScimLoginState("hash", 2, EARLIER, LockCause.DORMANCY, EARLIER, null)));

        users().replace(ScimIdentities.withActive(user, true,
                new ScimLoginState("hash", 0, null, null, EARLIER, null)), WRITTEN_AT);

        assertThat(loginStateOf(user))
                .extracting(ScimLoginState::failedLoginAttempts, ScimLoginState::lockedAt,
                        ScimLoginState::lockCause)
                .containsExactly(2, EARLIER, LockCause.DORMANCY);
    }

    @Test
    void a_replacement_of_the_profile_alone_keeps_the_credential() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));

        users().replace(ScimIdentities.withDisplayName(user, "Renamed"), WRITTEN_AT);

        assertThat(loginStateOf(user).passwordHash()).isEqualTo("hash");
    }

    /**
     * The credential, the flag and the basis are stored as given — a cleared flag included, which
     * no directory write's rule produces but the store must not second-guess.
     */
    @Test
    void a_replacement_stores_the_given_credential_flag_and_basis() {
        ScimUser user = users().create(ScimIdentities.userWithLoginState(unique("contract"),
                new ScimLoginState("hash", 0, null, null, EARLIER, EARLIER)));

        users().replace(ScimIdentities.withActive(user, true,
                new ScimLoginState("new-hash", 0, null, null, LATER, null)), WRITTEN_AT);

        assertThat(loginStateOf(user))
                .isEqualTo(new ScimLoginState("new-hash", 0, null, null, LATER, null));
    }

    /** A connector's password write owes a change: the replacement carries the flag it set. */
    @Test
    void a_replacement_stores_a_given_change_required_flag() {
        ScimUser user = users().create(ScimIdentities.userWithLoginState(unique("contract"),
                new ScimLoginState("hash", 0, null, null, EARLIER, null)));

        users().replace(ScimIdentities.withActive(user, true,
                new ScimLoginState("new-hash", 0, null, null, EARLIER, LATER)), WRITTEN_AT);

        assertThat(loginStateOf(user).passwordChangeRequiredSince()).isEqualTo(LATER);
    }

    /**
     * Activating a User through a replacement stores the login state it was given: the store
     * performs no Reactivation of its own, which is the login-state module's decision.
     */
    @Test
    void a_replacement_activating_the_user_stores_the_login_state_it_was_given() {
        ScimUser user = users().create(ScimIdentities.withActive(
                ScimIdentities.inactiveUser(unique("contract")), false,
                new ScimLoginState("hash", 0, null, null, EARLIER, null)));

        users().replace(ScimIdentities.withActive(user, true, user.login()), WRITTEN_AT);

        assertThat(loginStateOf(user))
                .isEqualTo(new ScimLoginState("hash", 0, null, null, EARLIER, null));
    }

    @Test
    void a_replacement_advances_the_version_exactly_once() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));

        users().replace(ScimIdentities.withDisplayName(user, "Renamed"), WRITTEN_AT);

        assertThat(versionOf(user.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION + 1, WRITTEN_AT));
    }

    @Test
    void a_replacement_reports_the_user_as_it_now_stands() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimUser renamed = ScimIdentities.withDisplayName(user, "Renamed");

        assertThat(users().replace(renamed, WRITTEN_AT)).contains(new ScimUser(user.id(),
                renamed.profile(), user.login(), null, ScimUser.INITIAL_VERSION + 1,
                ScimIdentities.NOW, WRITTEN_AT));
    }

    @Test
    void a_replacement_of_a_missing_user_reports_absence() {
        assertThat(users().replace(ScimIdentities.user(unique("contract")), WRITTEN_AT)).isEmpty();
    }

    @Test
    void a_replacement_to_a_normalized_user_name_another_user_holds_is_refused() {
        ScimUser holder = users().create(ScimIdentities.user(unique("contract")));
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimUser takingTheName = new ScimUser(user.id(),
                ScimIdentities.profile(holder.profile().userName().toUpperCase(), true),
                user.login(), null, user.version(), user.createdAt(), user.lastModifiedAt());

        assertThatThrownBy(() -> users().replace(takingTheName, WRITTEN_AT))
                .isInstanceOf(DuplicateUserNameException.class);
    }

    /** A Group renders each member by its label, so a moved label is a change to the Group too. */
    @Test
    void a_replacement_moving_the_member_label_advances_each_group_of_the_user() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimGroup group = groups().create(ScimIdentities.group(unique("contract"), user));

        users().replace(ScimIdentities.withDisplayName(user, "Renamed"), WRITTEN_AT);

        assertThat(groupVersionOf(group.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION + 1, WRITTEN_AT));
    }

    /** The label is the {@code displayName} when there is one, so its {@code userName} is not it. */
    @Test
    void a_replacement_keeping_the_member_label_leaves_the_groups_of_the_user() {
        ScimUser user = users().create(
                ScimIdentities.withDisplayName(ScimIdentities.user(unique("contract")), "Ada"));
        ScimGroup group = groups().create(ScimIdentities.group(unique("contract"), user));

        users().replace(ScimIdentities.withActive(user, false, user.login()), WRITTEN_AT);

        assertThat(groupVersionOf(group.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION, ScimIdentities.NOW));
    }

    // ---- advanceVersion ------------------------------------------------------------------------

    @Test
    void advancing_the_version_moves_it_once_and_records_when() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));

        users().advanceVersion(user.id(), WRITTEN_AT);

        assertThat(versionOf(user.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION + 1, WRITTEN_AT));
    }

    /** An alias is written for the calling connector alone; no Group renders it. */
    @Test
    void advancing_the_version_leaves_the_groups_of_the_user() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimGroup group = groups().create(ScimIdentities.group(unique("contract"), user));

        users().advanceVersion(user.id(), WRITTEN_AT);

        assertThat(groupVersionOf(group.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION, ScimIdentities.NOW));
    }

    // ---- deleteById ----------------------------------------------------------------------------

    @Test
    void a_deleted_user_is_gone() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));

        users().deleteById(user.id(), WRITTEN_AT);

        assertThat(users().findById(user.id())).isEmpty();
    }

    @Test
    void a_deleted_user_leaves_no_membership() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimGroup group = groups().create(ScimIdentities.group(unique("contract"), user));

        users().deleteById(user.id(), WRITTEN_AT);

        assertThat(groups().findById(group.id()).orElseThrow().hasMember(user.id())).isFalse();
    }

    @Test
    void deleting_a_user_advances_each_group_it_was_in() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimGroup group = groups().create(ScimIdentities.group(unique("contract"), user));

        users().deleteById(user.id(), WRITTEN_AT);

        assertThat(groupVersionOf(group.id()))
                .isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION + 1, WRITTEN_AT));
    }

    @Test
    void deleting_a_user_leaves_the_other_users() {
        ScimUser user = users().create(ScimIdentities.user(unique("contract")));
        ScimUser other = users().create(ScimIdentities.user(unique("contract")));

        users().deleteById(user.id(), WRITTEN_AT);

        assertThat(users().findById(other.id())).isPresent();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Frees the Bootstrap Admin's reservation by deleting whoever holds it, for a case that claims
     * it: the reservation is held once per store. A store shared with other tests must therefore
     * undo each case's writes afterwards.
     */
    private void releaseTheBootstrapAdminReservation() {
        ScimIdentities.releaseReservation(
                users(), ReservedResourceName.BOOTSTRAP_ADMIN, WRITTEN_AT);
    }

    /** A new User with every profile attribute and every login column set. */
    private static ScimUser fullyDescribed(String userName) {
        return fullyDescribed(userName, ScimIdentities.userWithLoginState(userName,
                new ScimLoginState("hash", 1, null, null, EARLIER, LATER)));
    }

    /** {@code user} with every profile attribute set, under {@code userName}. */
    private static ScimUser fullyDescribed(String userName, ScimUser user) {
        ScimUserProfile profile = new ScimUserProfile(
                userName,
                new ScimName("Dr. Ada M. Lovelace", "Lovelace", "Ada", "M.", "Dr.", "PhD"),
                "Ada Lovelace",
                "en-GB",
                "en-GB",
                "Europe/London",
                true,
                List.of(new ScimEmail(userName + "@example.test", "work", true),
                        new ScimEmail(userName + "@home.example.test", "home", false)));
        return new ScimUser(user.id(), profile, user.login(), null, user.version(),
                user.createdAt(), user.lastModifiedAt());
    }

    private ScimLoginState loginStateOf(ScimUser user) {
        return users().findById(user.id()).orElseThrow().login();
    }

    /** The User's version and {@code meta.lastModified}. */
    private Tuple versionOf(UUID userId) {
        ScimUser read = users().findById(userId).orElseThrow();
        return Tuple.tuple(read.version(), read.lastModifiedAt());
    }

    /** The Group's version and {@code meta.lastModified}. */
    private Tuple groupVersionOf(UUID groupId) {
        ScimGroup read = groups().findById(groupId).orElseThrow();
        return Tuple.tuple(read.version(), read.lastModifiedAt());
    }

}
