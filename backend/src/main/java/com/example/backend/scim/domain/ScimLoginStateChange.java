package com.example.backend.scim.domain;

import java.util.Objects;

/**
 * What one login-state transition changed: the state the login-state module read under the User's
 * lock, and the state it decided. The rule {@link ScimLoginStateRepository#writeChange} writes by,
 * held here once so that the SQL Server adapter and the in-memory store read the same answer
 * rather than each keeping a copy.
 *
 * <p>Four column sets can move, each compared on its own: the failure run (the count), the lock
 * (its instant and its cause, which are set and cleared together), the dormancy basis, and the
 * change-required flag. A set on which {@code before} and {@code after} agree is not written, so
 * whatever a concurrent writer stored there since — a writer the User's lock did not hold out — is
 * kept, and when they agree everywhere nothing is written at all. The credential is not among
 * them whatever the two states hold: a change of credential is a completed password change or a
 * directory replacement, never a login-state diff.
 *
 * @param before the state as read under the User's lock
 * @param after  the state the transition decided
 */
public record ScimLoginStateChange(ScimLoginState before, ScimLoginState after) {

    /** Whether the failure run's count differs. */
    public boolean movesFailureRun() {
        return before.failedLoginAttempts() != after.failedLoginAttempts();
    }

    /** Whether the lock differs: its instant, its cause, or whether one stands at all. */
    public boolean movesLock() {
        return !Objects.equals(before.lockedAt(), after.lockedAt())
                || before.lockCause() != after.lockCause();
    }

    /** Whether the dormancy basis differs. */
    public boolean movesDormancyBasis() {
        return !Objects.equals(before.lastAuthenticatedAt(), after.lastAuthenticatedAt());
    }

    /** Whether the change-required flag differs: its instant, or whether it is set at all. */
    public boolean movesChangeRequired() {
        return !Objects.equals(
                before.passwordChangeRequiredSince(), after.passwordChangeRequiredSince());
    }

    /**
     * {@code stored} with every column set this change moves taken from {@link #after}, and every
     * other — the credential always — kept as stored. What a store holding the whole state at once
     * writes; a store writing columns writes the same sets by {@link #movesFailureRun} and the
     * others.
     */
    public ScimLoginState appliedTo(ScimLoginState stored) {
        boolean lock = movesLock();
        return new ScimLoginState(
                stored.passwordHash(),
                movesFailureRun() ? after.failedLoginAttempts() : stored.failedLoginAttempts(),
                lock ? after.lockedAt() : stored.lockedAt(),
                lock ? after.lockCause() : stored.lockCause(),
                movesDormancyBasis() ? after.lastAuthenticatedAt() : stored.lastAuthenticatedAt(),
                movesChangeRequired()
                        ? after.passwordChangeRequiredSince()
                        : stored.passwordChangeRequiredSince());
    }
}
