package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A User's authentication state, and the transitions the login path drives it through.
 *
 * <p>These are the rules the deleted account aggregate's tests held, moved to where the state now
 * lives.
 */
class ScimLoginStateTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final LockoutPolicy AFTER_THREE = new LockoutPolicy(3);

    /**
     * The dormancy basis survives every failure-run transition. A rejected login or an Unlock is
     * not an authentication, and a transition that dropped the basis would make a User who once
     * logged in look as if it never had.
     */
    @Test
    void every_failure_run_transition_carries_the_dormancy_basis_unchanged() {
        Instant last = NOW.minusSeconds(3_600);
        ScimLoginState running = new ScimLoginState("hash", 2, null, last);
        ScimLoginState locked = new ScimLoginState("hash", 3, NOW, last);

        assertThat(running.withFailureRecorded(AFTER_THREE, NOW).lastAuthenticatedAt())
                .as("the failure that locks").isEqualTo(last);
        assertThat(new ScimLoginState("hash", 0, null, last)
                .withFailureRecorded(AFTER_THREE, NOW).lastAuthenticatedAt())
                .as("a failure that does not lock").isEqualTo(last);
        assertThat(running.withFailureCounted().lastAuthenticatedAt()).isEqualTo(last);
        assertThat(locked.withFailureRunCleared().lastAuthenticatedAt()).isEqualTo(last);
    }

    /**
     * The change-required flag survives every failure-run transition: a rejected login, a
     * counted failure and an Unlock's clearing all leave it standing. Only a completed change
     * clears it, and that is a port operation, not a transition here.
     */
    @Test
    void every_failure_run_transition_carries_the_change_required_flag_unchanged() {
        ScimLoginState running = new ScimLoginState("hash", 2, null, null, NOW);
        ScimLoginState locked = new ScimLoginState("hash", 3, NOW, null, NOW);

        assertThat(running.withFailureRecorded(AFTER_THREE, NOW).passwordChangeRequiredSince())
                .as("the failure that locks").isEqualTo(NOW);
        assertThat(new ScimLoginState("hash", 0, null, null, NOW)
                .withFailureRecorded(AFTER_THREE, NOW).passwordChangeRequiredSince())
                .as("a failure that does not lock").isEqualTo(NOW);
        assertThat(running.withFailureCounted().passwordChangeRequiredSince()).isEqualTo(NOW);
        assertThat(locked.withFailureRunCleared().passwordChangeRequiredSince()).isEqualTo(NOW);
    }

    @Test
    void requiring_a_change_dates_the_flag_and_keeps_everything_else() {
        Instant last = NOW.minusSeconds(60);
        ScimLoginState state = new ScimLoginState("hash", 2, NOW, last);
        Instant later = NOW.plusSeconds(10);

        ScimLoginState flagged = state.withPasswordChangeRequired(later);

        assertThat(state.isPasswordChangeRequired()).isFalse();
        assertThat(flagged).isEqualTo(new ScimLoginState("hash", 2, NOW, last, later));
        assertThat(flagged.isPasswordChangeRequired()).isTrue();
        assertThat(flagged.withPasswordChangeRequired(later.plusSeconds(5))
                .passwordChangeRequiredSince())
                .as("a newly imposed credential re-dates the flag")
                .isEqualTo(later.plusSeconds(5));
        assertThatThrownBy(() -> state.withPasswordChangeRequired(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_state_built_without_an_authentication_has_none() {
        assertThat(new ScimLoginState("hash", 0, null).lastAuthenticatedAt()).isNull();
        assertThat(ScimLoginState.of("hash").lastAuthenticatedAt()).isNull();
        assertThat(ScimLoginState.CREDENTIALLESS.lastAuthenticatedAt()).isNull();
    }

    @Test
    void a_negative_failure_run_is_refused() {
        assertThatThrownBy(() -> new ScimLoginState("hash", -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void a_credentialless_state_has_no_password_and_no_history() {
        assertThat(ScimLoginState.CREDENTIALLESS.hasPassword()).isFalse();
        assertThat(ScimLoginState.CREDENTIALLESS.failedLoginAttempts()).isZero();
        assertThat(ScimLoginState.CREDENTIALLESS.isLocked()).isFalse();
    }

    @Test
    void a_null_hash_yields_the_credentialless_state_rather_than_a_state_holding_null() {
        assertThat(ScimLoginState.of(null)).isSameAs(ScimLoginState.CREDENTIALLESS);
    }

    @Test
    void a_state_built_from_a_hash_has_a_password_and_no_history() {
        ScimLoginState state = ScimLoginState.of("hash");

        assertThat(state.hasPassword()).isTrue();
        assertThat(state.passwordHash()).isEqualTo("hash");
        assertThat(state.failedLoginAttempts()).isZero();
        assertThat(state.isLocked()).isFalse();
    }

    @Test
    void a_recorded_failure_below_the_limit_counts_but_does_not_lock() {
        ScimLoginState after = ScimLoginState.of("hash").withFailureRecorded(AFTER_THREE, NOW);

        assertThat(after.failedLoginAttempts()).isEqualTo(1);
        assertThat(after.isLocked()).isFalse();
        assertThat(after.lockedAt()).isNull();
    }

    @Test
    void reaching_the_limit_locks_as_of_the_instant_given() {
        ScimLoginState after = new ScimLoginState("hash", 2, null)
                .withFailureRecorded(AFTER_THREE, NOW);

        assertThat(after.failedLoginAttempts()).isEqualTo(3);
        assertThat(after.isLocked()).isTrue();
        assertThat(after.lockedAt()).isEqualTo(NOW);
    }

    @Test
    void a_locked_state_is_returned_unchanged_so_attempts_neither_count_nor_deepen_the_lock() {
        ScimLoginState locked = new ScimLoginState("hash", 3, NOW);

        ScimLoginState after = locked.withFailureRecorded(AFTER_THREE, NOW.plusSeconds(600));

        assertThat(after).isSameAs(locked);
        assertThat(after.failedLoginAttempts()).isEqualTo(3);
        assertThat(after.lockedAt()).isEqualTo(NOW);
    }

    @Test
    void a_counted_failure_lengthens_the_run_and_never_locks_whatever_the_limit_says() {
        ScimLoginState after = new ScimLoginState("hash", 99, null).withFailureCounted();

        assertThat(after.failedLoginAttempts()).isEqualTo(100);
        assertThat(after.isLocked()).isFalse();
    }

    @Test
    void a_counted_failure_leaves_an_existing_lock_exactly_as_it_stood() {
        ScimLoginState after = new ScimLoginState("hash", 3, NOW).withFailureCounted();

        assertThat(after.lockedAt()).isEqualTo(NOW);
        assertThat(after.failedLoginAttempts()).isEqualTo(4);
    }

    @Test
    void clearing_the_run_drops_both_the_count_and_the_lock() {
        ScimLoginState after = new ScimLoginState("hash", 3, NOW).withFailureRunCleared();

        assertThat(after.failedLoginAttempts()).isZero();
        assertThat(after.isLocked()).isFalse();
        assertThat(after.passwordHash()).isEqualTo("hash");
    }

    /**
     * A run that has not reached the threshold is still a run to clear: no lock stands, but the
     * count does, so the value is not "nothing to clear" and an accepted login must write it.
     */
    @Test
    void clearing_a_run_that_has_not_locked_drops_the_count() {
        ScimLoginState running = new ScimLoginState("hash", 2, null);

        assertThat(running.withFailureRunCleared()).isEqualTo(ScimLoginState.of("hash"));
    }

    /**
     * Returned by identity so a caller can skip a pointless write. That is the only observable
     * difference between skipping the write and performing a redundant one, which is why the
     * assertion is on identity rather than on equality.
     */
    @Test
    void clearing_a_state_with_nothing_to_clear_returns_the_same_value() {
        ScimLoginState clean = ScimLoginState.of("hash");

        assertThat(clean.withFailureRunCleared()).isSameAs(clean);
    }

    @Test
    void clearing_preserves_a_credentialless_state_rather_than_inventing_a_hash() {
        ScimLoginState after = new ScimLoginState(null, 2, null).withFailureRunCleared();

        assertThat(after.hasPassword()).isFalse();
        assertThat(after.passwordHash()).isNull();
    }

    // ---- the lock's cause (ADR 0007) -------------------------------------------------------

    /** A lock and its cause are set and cleared together; a state where they disagree is refused. */
    @Test
    void a_lock_and_its_cause_cannot_disagree() {
        assertThatThrownBy(() -> new ScimLoginState("hash", 0, NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set and cleared together");
        assertThatThrownBy(() -> new ScimLoginState(
                        "hash", 0, null, LockCause.DORMANCY, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new ScimLoginState("hash", 0, NOW, LockCause.DORMANCY, null, null).lockCause())
                .isEqualTo(LockCause.DORMANCY);
        assertThat(new ScimLoginState("hash", 0, null, null, null, null).lockCause()).isNull();
    }

    /** The shapes predating the cause read a standing lock as a failure lock, the only kind. */
    @Test
    void a_state_built_without_a_cause_reads_a_lock_as_a_failure_lock() {
        assertThat(new ScimLoginState("hash", 3, NOW).lockCause()).isEqualTo(LockCause.FAILURES);
        assertThat(new ScimLoginState("hash", 3, NOW, null, null).lockCause())
                .isEqualTo(LockCause.FAILURES);
        assertThat(new ScimLoginState("hash", 0, null).lockCause()).isNull();
        assertThat(ScimLoginState.CREDENTIALLESS.lockCause()).isNull();
    }

    @Test
    void the_failure_that_locks_records_failures_as_the_cause() {
        ScimLoginState locked = new ScimLoginState("hash", 2, null)
                .withFailureRecorded(AFTER_THREE, NOW);

        assertThat(locked.lockedAt()).isEqualTo(NOW);
        assertThat(locked.lockCause()).isEqualTo(LockCause.FAILURES);
        assertThat(new ScimLoginState("hash", 0, null).withFailureRecorded(AFTER_THREE, NOW)
                .lockCause()).as("a failure that does not lock sets no cause").isNull();
    }

    /**
     * The dormancy lock: the lock as of now with DORMANCY, and every other component — the
     * failure run, the credential, the basis, the flag — exactly as it was.
     */
    @Test
    void a_dormancy_lock_sets_the_lock_and_its_cause_and_nothing_else() {
        Instant last = NOW.minusSeconds(60);
        Instant flagged = NOW.minusSeconds(30);
        ScimLoginState before = new ScimLoginState("hash", 2, null, null, last, flagged);

        ScimLoginState locked = before.withDormancyLock(NOW);

        assertThat(locked).isEqualTo(
                new ScimLoginState("hash", 2, NOW, LockCause.DORMANCY, last, flagged));
        assertThat(locked.isLocked()).isTrue();
    }

    /** An already locked User keeps its lock, its instant and its cause. */
    @Test
    void a_dormancy_lock_over_a_standing_lock_changes_nothing() {
        ScimLoginState failureLock = new ScimLoginState("hash", 3, NOW.minusSeconds(5));

        assertThat(failureLock.withDormancyLock(NOW)).isSameAs(failureLock);
        assertThat(failureLock.withDormancyLock(NOW).lockCause()).isEqualTo(LockCause.FAILURES);
    }

    /** A failed attempt against a dormancy lock neither counts nor changes the cause. */
    @Test
    void a_dormancy_lock_survives_failed_attempts_and_clears_with_the_run() {
        ScimLoginState locked = ScimLoginState.of("hash").withDormancyLock(NOW);

        assertThat(locked.withFailureRecorded(AFTER_THREE, NOW.plusSeconds(1))).isSameAs(locked);
        assertThat(locked.withFailureCounted().lockCause()).isEqualTo(LockCause.DORMANCY);
        assertThat(locked.withPasswordChangeRequired(NOW).lockCause())
                .isEqualTo(LockCause.DORMANCY);

        ScimLoginState cleared = locked.withFailureRunCleared();
        assertThat(cleared.lockedAt()).isNull();
        assertThat(cleared.lockCause()).isNull();
    }

    /** Restarting the dormancy basis moves only the last authentication; the rest is kept. */
    @Test
    void restarting_the_dormancy_basis_keeps_everything_else() {
        Instant required = NOW.minusSeconds(30);
        ScimLoginState state = new ScimLoginState(
                "hash", 2, NOW, LockCause.DORMANCY, NOW.minusSeconds(60), required);
        Instant later = NOW.plusSeconds(10);

        assertThat(state.withDormancyBasisReset(later)).isEqualTo(new ScimLoginState(
                "hash", 2, NOW, LockCause.DORMANCY, later, required));
        assertThatThrownBy(() -> state.withDormancyBasisReset(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Replacing the credential — or removing it — moves only the credential; the rest is kept. */
    @ParameterizedTest(name = "to {0}")
    @NullSource
    @ValueSource(strings = "new-hash")
    void replacing_the_credential_keeps_everything_else(String passwordHash) {
        Instant required = NOW.minusSeconds(30);
        ScimLoginState state = new ScimLoginState(
                "hash", 2, NOW, LockCause.DORMANCY, NOW.minusSeconds(60), required);

        assertThat(state.withCredential(passwordHash)).isEqualTo(new ScimLoginState(
                passwordHash, 2, NOW, LockCause.DORMANCY, NOW.minusSeconds(60), required));
    }

    // ---- what a transition changed (ScimLoginStateChange) ----------------------------------

    /** The column sets a login-state change can move, as the store writes them. */
    private enum ColumnSet { FAILURE_RUN, LOCK, DORMANCY_BASIS, CHANGE_REQUIRED }

    private static final Instant BASIS = NOW.minusSeconds(60);

    private static final Instant FLAGGED = NOW.minusSeconds(30);

    /** The state a transition read under the User's lock: every column set holding a value. */
    private static final ScimLoginState READ =
            new ScimLoginState("hash", 2, NOW, LockCause.FAILURES, BASIS, FLAGGED);

    /**
     * What a concurrent writer stored since the read, different in every column, so that a set
     * the change does not move reads back as this and a set it moves reads back as its after.
     */
    private static final ScimLoginState STORED = new ScimLoginState(
            "stored-hash", 5, NOW.plusSeconds(1), LockCause.DORMANCY,
            NOW.minusSeconds(61), NOW.minusSeconds(31));

    /**
     * Each difference a transition can make from {@link #READ}, the one column set it moves, and
     * {@link #STORED} with that change applied. Sides that agree — built from equal values, not
     * the same objects — and a difference in the credential alone move nothing.
     */
    static Stream<Arguments> changes() {
        return Stream.of(
                Arguments.of("the failure run",
                        new ScimLoginState("hash", 3, NOW, LockCause.FAILURES, BASIS, FLAGGED),
                        EnumSet.of(ColumnSet.FAILURE_RUN),
                        new ScimLoginState("stored-hash", 3, NOW.plusSeconds(1),
                                LockCause.DORMANCY, NOW.minusSeconds(61), NOW.minusSeconds(31))),
                Arguments.of("a lifted lock",
                        new ScimLoginState("hash", 2, null, null, BASIS, FLAGGED),
                        EnumSet.of(ColumnSet.LOCK),
                        new ScimLoginState("stored-hash", 5, null, null,
                                NOW.minusSeconds(61), NOW.minusSeconds(31))),
                Arguments.of("the lock's instant alone",
                        new ScimLoginState(
                                "hash", 2, NOW.plusSeconds(9), LockCause.FAILURES, BASIS, FLAGGED),
                        EnumSet.of(ColumnSet.LOCK),
                        new ScimLoginState("stored-hash", 5, NOW.plusSeconds(9),
                                LockCause.FAILURES, NOW.minusSeconds(61), NOW.minusSeconds(31))),
                Arguments.of("the lock's cause alone",
                        new ScimLoginState("hash", 2, NOW, LockCause.DORMANCY, BASIS, FLAGGED),
                        EnumSet.of(ColumnSet.LOCK),
                        new ScimLoginState("stored-hash", 5, NOW, LockCause.DORMANCY,
                                NOW.minusSeconds(61), NOW.minusSeconds(31))),
                Arguments.of("the dormancy basis",
                        new ScimLoginState("hash", 2, NOW, LockCause.FAILURES,
                                NOW.plusSeconds(10), FLAGGED),
                        EnumSet.of(ColumnSet.DORMANCY_BASIS),
                        new ScimLoginState("stored-hash", 5, NOW.plusSeconds(1),
                                LockCause.DORMANCY, NOW.plusSeconds(10), NOW.minusSeconds(31))),
                Arguments.of("a cleared change-required flag",
                        new ScimLoginState("hash", 2, NOW, LockCause.FAILURES, BASIS, null),
                        EnumSet.of(ColumnSet.CHANGE_REQUIRED),
                        new ScimLoginState("stored-hash", 5, NOW.plusSeconds(1),
                                LockCause.DORMANCY, NOW.minusSeconds(61), null)),
                Arguments.of("a re-dated change-required flag",
                        new ScimLoginState("hash", 2, NOW, LockCause.FAILURES, BASIS,
                                NOW.plusSeconds(20)),
                        EnumSet.of(ColumnSet.CHANGE_REQUIRED),
                        new ScimLoginState("stored-hash", 5, NOW.plusSeconds(1),
                                LockCause.DORMANCY, NOW.minusSeconds(61), NOW.plusSeconds(20))),
                Arguments.of("the credential alone",
                        new ScimLoginState(
                                "other-hash", 2, NOW, LockCause.FAILURES, BASIS, FLAGGED),
                        EnumSet.noneOf(ColumnSet.class),
                        STORED),
                Arguments.of("nothing: the two sides agree",
                        new ScimLoginState("hash", 2, Instant.parse("2026-01-01T00:00:00Z"),
                                LockCause.FAILURES, Instant.parse("2025-12-31T23:59:00Z"),
                                Instant.parse("2025-12-31T23:59:30Z")),
                        EnumSet.noneOf(ColumnSet.class),
                        STORED));
    }

    private static Set<ColumnSet> moved(ScimLoginStateChange change) {
        Set<ColumnSet> moved = EnumSet.noneOf(ColumnSet.class);
        if (change.movesFailureRun()) {
            moved.add(ColumnSet.FAILURE_RUN);
        }
        if (change.movesLock()) {
            moved.add(ColumnSet.LOCK);
        }
        if (change.movesDormancyBasis()) {
            moved.add(ColumnSet.DORMANCY_BASIS);
        }
        if (change.movesChangeRequired()) {
            moved.add(ColumnSet.CHANGE_REQUIRED);
        }
        return moved;
    }

    @Test
    void a_change_carries_the_state_read_and_the_state_decided() {
        ScimLoginState decided = READ.withFailureRunCleared();

        ScimLoginStateChange change = new ScimLoginStateChange(READ, decided);

        assertThat(List.of(change.before(), change.after())).containsExactly(READ, decided);
    }

    /** Each column set is compared on its own, and the credential is never one of them. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("changes")
    void a_change_moves_exactly_the_column_set_that_differs(
            String difference, ScimLoginState after, Set<ColumnSet> expected,
            ScimLoginState applied) {
        assertThat(moved(new ScimLoginStateChange(READ, after))).isEqualTo(expected);
    }

    /**
     * Applied to what is stored now, a change takes each set it moves from its after and keeps
     * every other set — and the credential always — as stored, so a concurrent write to a set the
     * transition did not change survives it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("changes")
    void a_change_applied_to_the_stored_state_replaces_only_the_sets_it_moves(
            String difference, ScimLoginState after, Set<ColumnSet> moved,
            ScimLoginState expected) {
        assertThat(new ScimLoginStateChange(READ, after).appliedTo(STORED)).isEqualTo(expected);
    }
}
