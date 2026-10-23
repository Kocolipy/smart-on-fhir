package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScimLoginStateRepository} does, whatever stores the User row: the SQL Server
 * adapter, and the in-memory store the login-state module's unit tests run against. Each write
 * has a rule about which columns it touches and whether it moves the Resource version — for the
 * change write, which columns move for which difference, and that nothing else does; a
 * unit test that passes against the in-memory store says something about production only while
 * both follow those rules, so each runs this.
 *
 * <p>Users are arranged and read back through the directory port, {@link #users()}, because the
 * two ports share the row: a column one port writes is the other's to show. Every instant is in
 * whole seconds, so it survives the {@code DATETIMEOFFSET(6)} columns unchanged.
 *
 * <p>Concurrency is out of scope: {@code LoginStateSerializationIntegrationTests} proves the
 * serialization the in-memory store does not model.
 */
public abstract class ScimLoginStateRepositoryContract {

    /** The login-state port under test. */
    protected abstract ScimLoginStateRepository loginStates();

    /** The directory port over the same Users, through which a case arranges and reads back. */
    protected abstract ScimUserRepository users();

    private static final Instant EARLIER = Instant.parse("2026-03-01T08:00:00Z");

    private static final Instant LATER = Instant.parse("2026-05-01T08:00:00Z");

    /** When a write that advances the version is made: after everything stored here. */
    private static final Instant WRITTEN_AT = Instant.parse("2026-07-01T08:00:00Z");

    /** The dormancy cutoff the candidate cases ask about. */
    private static final Instant CUTOFF = Instant.parse("2026-04-01T08:00:00Z");

    // ---- findLoginState ------------------------------------------------------------------------

    @Test
    void the_login_state_is_read_as_stored() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, LATER));

        assertThat(loginStates().findLoginState(user.id())).contains(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, LATER));
    }

    @Test
    void a_missing_user_has_no_login_state() {
        assertThat(loginStates().findLoginState(UUID.randomUUID())).isEmpty();
    }

    // ---- isActive ------------------------------------------------------------------------------

    @Test
    void an_active_user_is_read_as_active() {
        ScimUser user = stored(ScimLoginState.of("hash"));

        assertThat(loginStates().isActive(user.id())).isTrue();
    }

    @Test
    void a_deactivated_user_is_read_as_not_active() {
        ScimUser user = users().create(ScimIdentities.inactiveUser(unique()));

        assertThat(loginStates().isActive(user.id())).isFalse();
    }

    @Test
    void a_missing_user_is_not_active() {
        assertThat(loginStates().isActive(UUID.randomUUID())).isFalse();
    }

    // ---- writeChange ---------------------------------------------------------------------------
    //
    // Each column case stores a state the change's two sides both disagree with everywhere but in
    // the column under test — as a concurrent writer would leave it — so a column written although
    // the two sides agree on it shows up as a reverted value, and the credential, which both sides
    // carry differently from the row, shows up if it is written at all.

    @Test
    void a_changed_failure_run_writes_the_count_and_nothing_else() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 1, EARLIER, LockCause.FAILURES, EARLIER, EARLIER));

        loginStates().writeChange(user.id(),
                new ScimLoginState("read-hash", 1, null, null, LATER, null),
                new ScimLoginState("decided-hash", 2, null, null, LATER, null));

        assertThat(loginStateOf(user)).isEqualTo(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, EARLIER));
    }

    @Test
    void a_changed_lock_writes_its_instant_and_cause_and_nothing_else() {
        ScimUser user = stored(new ScimLoginState("hash", 3, null, null, EARLIER, EARLIER));

        loginStates().writeChange(user.id(),
                new ScimLoginState("read-hash", 0, null, null, LATER, null),
                new ScimLoginState("decided-hash", 0, LATER, LockCause.FAILURES, LATER, null));

        assertThat(loginStateOf(user)).isEqualTo(new ScimLoginState(
                "hash", 3, LATER, LockCause.FAILURES, EARLIER, EARLIER));
    }

    @Test
    void a_lifted_lock_clears_its_instant_and_cause() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 0, EARLIER, LockCause.DORMANCY, EARLIER, null));
        ScimLoginState read = loginStateOf(user);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("hash", 0, null, null, EARLIER, null));

        assertThat(loginStateOf(user)).isEqualTo(
                new ScimLoginState("hash", 0, null, null, EARLIER, null));
    }

    /** A lock re-dated under the same cause is a changed lock. */
    @Test
    void a_lock_changed_in_its_instant_alone_writes_the_instant() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 0, EARLIER, LockCause.FAILURES, EARLIER, null));
        ScimLoginState read = loginStateOf(user);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("hash", 0, LATER, LockCause.FAILURES, EARLIER, null));

        assertThat(loginStateOf(user).lockedAt()).isEqualTo(LATER);
    }

    /** The cause is part of the lock: a lock differing in its cause alone is a changed lock. */
    @Test
    void a_lock_changed_in_its_cause_alone_writes_the_cause() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 0, EARLIER, LockCause.FAILURES, EARLIER, null));
        ScimLoginState read = loginStateOf(user);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("hash", 0, EARLIER, LockCause.DORMANCY, EARLIER, null));

        assertThat(loginStateOf(user).lockCause()).isEqualTo(LockCause.DORMANCY);
    }

    @Test
    void a_changed_dormancy_basis_writes_the_basis_and_nothing_else() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, EARLIER));

        loginStates().writeChange(user.id(),
                new ScimLoginState("read-hash", 0, null, null, EARLIER, null),
                new ScimLoginState("decided-hash", 0, null, null, LATER, null));

        assertThat(loginStateOf(user)).isEqualTo(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, LATER, EARLIER));
    }

    @Test
    void a_changed_change_required_flag_writes_the_flag_and_nothing_else() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, null));

        loginStates().writeChange(user.id(),
                new ScimLoginState("read-hash", 0, null, null, LATER, EARLIER),
                new ScimLoginState("decided-hash", 0, null, null, LATER, LATER));

        assertThat(loginStateOf(user)).isEqualTo(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, LATER));
    }

    /** Every column set moved at once, as an Unlock moves them: each is written. */
    @Test
    void a_change_of_every_column_set_writes_each_of_them() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 3, EARLIER, LockCause.FAILURES, EARLIER, null));
        ScimLoginState read = loginStateOf(user);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("hash", 0, null, null, LATER, LATER));

        assertThat(loginStateOf(user)).isEqualTo(
                new ScimLoginState("hash", 0, null, null, LATER, LATER));
    }

    @Test
    void a_change_whose_two_sides_agree_writes_nothing() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 1, EARLIER, LockCause.FAILURES, EARLIER, EARLIER));
        ScimLoginState read = new ScimLoginState(
                "read-hash", 0, LATER, LockCause.DORMANCY, LATER, LATER);

        loginStates().writeChange(user.id(), read, read);

        assertThat(loginStateOf(user)).isEqualTo(new ScimLoginState(
                "hash", 1, EARLIER, LockCause.FAILURES, EARLIER, EARLIER));
    }

    /**
     * The hash in both states is the one the event read; a password changed since then survives
     * the write, even when the decided state carries a hash of its own.
     */
    @Test
    void a_change_keeps_a_credential_written_since_it_was_read() {
        ScimUser user = stored(new ScimLoginState("hash", 0, null, null, EARLIER, null));
        ScimLoginState read = loginStateOf(user);
        loginStates().completePasswordChange(user.id(), "new-hash", WRITTEN_AT);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("decided-hash", 1, null, null, EARLIER, null));

        assertThat(loginStateOf(user).passwordHash()).isEqualTo("new-hash");
    }

    /** A connector's change landing between an event's read and its write survives the write. */
    @Test
    void a_change_keeps_profile_attributes_written_since_it_was_read() {
        ScimUser user = stored(new ScimLoginState("hash", 0, null, null, EARLIER, null));
        ScimLoginState read = loginStateOf(user);
        users().replace(ScimIdentities.withDisplayName(user, "Renamed"), WRITTEN_AT);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("hash", 1, LATER, LockCause.FAILURES, LATER, LATER));

        assertThat(users().findById(user.id()).orElseThrow().profile().displayName())
                .isEqualTo("Renamed");
    }

    /**
     * None of the login-state columns is a SCIM attribute: a rejected login, an Unlock or a forced
     * change must not move a connector's ETag or {@code meta.lastModified}.
     */
    @Test
    void a_change_does_not_advance_the_version() {
        ScimUser user = stored(new ScimLoginState("hash", 1, null, null, EARLIER, null));
        ScimLoginState read = loginStateOf(user);

        loginStates().writeChange(user.id(), read,
                new ScimLoginState("hash", 5, LATER, LockCause.FAILURES, LATER, LATER));

        assertThat(versionOf(user)).isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW));
    }

    @Test
    void a_change_of_a_missing_user_is_silently_nothing() {
        UUID missing = UUID.randomUUID();

        assertThatCode(() -> loginStates().writeChange(missing,
                new ScimLoginState("hash", 0, null, null, null, null),
                new ScimLoginState("hash", 1, LATER, LockCause.FAILURES, LATER, LATER)))
                .doesNotThrowAnyException();
    }

    // ---- imposeLock ----------------------------------------------------------------------------

    @Test
    void imposing_a_lock_where_none_stands_reports_that_it_applied() {
        ScimUser user = stored(new ScimLoginState("hash", 1, null, null, EARLIER, null));

        assertThat(loginStates().imposeLock(user.id(), new Lockout(LATER, LockCause.DORMANCY)))
                .isTrue();
    }

    @Test
    void imposing_a_lock_stores_its_instant_and_cause_and_nothing_else() {
        ScimUser user = stored(new ScimLoginState("hash", 1, null, null, EARLIER, EARLIER));

        loginStates().imposeLock(user.id(), new Lockout(LATER, LockCause.DORMANCY));

        assertThat(loginStateOf(user)).isEqualTo(
                new ScimLoginState("hash", 1, LATER, LockCause.DORMANCY, EARLIER, EARLIER));
    }

    @Test
    void imposing_a_lock_over_a_standing_one_reports_that_it_did_not_apply() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 3, EARLIER, LockCause.FAILURES, EARLIER, null));

        assertThat(loginStates().imposeLock(user.id(), new Lockout(LATER, LockCause.DORMANCY)))
                .isFalse();
    }

    /** A compare-and-set: the standing lock keeps its own instant and cause. */
    @Test
    void imposing_a_lock_over_a_standing_one_keeps_the_standing_lock() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 3, EARLIER, LockCause.FAILURES, EARLIER, null));

        loginStates().imposeLock(user.id(), new Lockout(LATER, LockCause.DORMANCY));

        assertThat(loginStateOf(user)).isEqualTo(
                new ScimLoginState("hash", 3, EARLIER, LockCause.FAILURES, EARLIER, null));
    }

    @Test
    void imposing_a_lock_does_not_advance_the_version() {
        ScimUser user = stored(new ScimLoginState("hash", 0, null, null, EARLIER, null));

        loginStates().imposeLock(user.id(), new Lockout(LATER, LockCause.DORMANCY));

        assertThat(versionOf(user)).isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW));
    }

    @Test
    void imposing_a_lock_on_a_missing_user_reports_that_it_did_not_apply() {
        assertThat(loginStates().imposeLock(UUID.randomUUID(),
                new Lockout(LATER, LockCause.DORMANCY))).isFalse();
    }

    // ---- completePasswordChange ----------------------------------------------------------------

    @Test
    void completing_a_password_change_writes_the_credential_and_clears_the_flag_alone() {
        ScimUser user = stored(new ScimLoginState(
                "hash", 2, EARLIER, LockCause.FAILURES, EARLIER, LATER));

        loginStates().completePasswordChange(user.id(), "new-hash", WRITTEN_AT);

        assertThat(loginStateOf(user)).isEqualTo(
                new ScimLoginState("new-hash", 2, EARLIER, LockCause.FAILURES, EARLIER, null));
    }

    /** {@code password} is a SCIM attribute, though never rendered: the version moves, once. */
    @Test
    void completing_a_password_change_advances_the_version_exactly_once() {
        ScimUser user = stored(new ScimLoginState("hash", 0, null, null, EARLIER, LATER));

        loginStates().completePasswordChange(user.id(), "new-hash", WRITTEN_AT);

        assertThat(versionOf(user)).isEqualTo(Tuple.tuple(ScimUser.INITIAL_VERSION + 1,
                WRITTEN_AT));
    }

    @Test
    void completing_a_password_change_reports_the_user_as_it_now_stands() {
        ScimUser user = stored(new ScimLoginState("hash", 0, null, null, EARLIER, LATER));

        Optional<ScimUser> reported =
                loginStates().completePasswordChange(user.id(), "new-hash", WRITTEN_AT);

        assertThat(reported).contains(new ScimUser(user.id(), user.profile(),
                new ScimLoginState("new-hash", 0, null, null, EARLIER, null), null,
                ScimUser.INITIAL_VERSION + 1, ScimIdentities.NOW, WRITTEN_AT));
    }

    @Test
    void completing_a_password_change_for_a_missing_user_reports_absence() {
        assertThat(loginStates().completePasswordChange(UUID.randomUUID(), "new-hash", WRITTEN_AT))
                .isEmpty();
    }

    // ---- findDormantUnlockedUserIds ------------------------------------------------------------

    /**
     * Exactly the unlocked, unreserved Users whose dormancy basis — the last authentication, or
     * the creation when there has been none — is strictly before the cutoff. Asked only about the
     * Users this case stored: a shared store may hold others.
     */
    @Test
    void the_dormancy_candidates_are_exactly_the_unlocked_users_dormant_before_the_cutoff() {
        ScimUser dormant = stored(new ScimLoginState("hash", 0, null, null, EARLIER, null));
        ScimUser neverAuthenticated = stored(ScimLoginState.of("hash"));
        ScimUser inactiveDormant = users().create(ScimIdentities.withActive(
                ScimIdentities.inactiveUser(unique()), false,
                new ScimLoginState("hash", 0, null, null, EARLIER, null)));
        ScimUser locked = stored(new ScimLoginState(
                "hash", 0, EARLIER, LockCause.FAILURES, EARLIER, null));
        ScimUser recent = stored(new ScimLoginState("hash", 0, null, null, LATER, null));
        ScimUser atTheCutoff = stored(new ScimLoginState("hash", 0, null, null, CUTOFF, null));
        ScimUser createdRecently = users().create(new ScimUser(UUID.randomUUID(),
                ScimIdentities.profile(unique(), true), ScimLoginState.of("hash"), null,
                ScimUser.INITIAL_VERSION, LATER, LATER));
        ScimUser reserved = reservedDormantUser();
        Set<UUID> asked = Set.of(dormant.id(), neverAuthenticated.id(), inactiveDormant.id(),
                locked.id(), recent.id(), atTheCutoff.id(), createdRecently.id(), reserved.id());

        List<UUID> candidates = loginStates().findDormantUnlockedUserIds(CUTOFF).stream()
                .filter(asked::contains)
                .toList();

        assertThat(candidates).containsExactlyInAnyOrder(
                dormant.id(), neverAuthenticated.id(), inactiveDormant.id());
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** An unreserved, active User created at {@link ScimIdentities#NOW} with this login state. */
    private ScimUser stored(ScimLoginState login) {
        return users().create(ScimIdentities.userWithLoginState(unique(), login));
    }

    /**
     * The Bootstrap Admin, dormant since {@link #EARLIER}. The reservation is held once per store,
     * so whoever holds it is deleted first — a store shared with other tests must therefore undo
     * this case's writes afterwards.
     */
    private ScimUser reservedDormantUser() {
        ScimIdentities.releaseReservation(
                users(), ReservedResourceName.BOOTSTRAP_ADMIN, WRITTEN_AT);
        return users().createReserved(
                ScimIdentities.userWithLoginState(unique(),
                        new ScimLoginState("hash", 0, null, null, EARLIER, null)),
                ReservedResourceName.BOOTSTRAP_ADMIN);
    }

    private ScimLoginState loginStateOf(ScimUser user) {
        return loginStates().findLoginState(user.id()).orElseThrow();
    }

    /** The User's version and {@code meta.lastModified}, as the directory reads them back. */
    private Tuple versionOf(ScimUser user) {
        ScimUser read = users().findById(user.id()).orElseThrow();
        return Tuple.tuple(read.version(), read.lastModifiedAt());
    }

    private static String unique() {
        return "contract-" + UUID.randomUUID();
    }
}
