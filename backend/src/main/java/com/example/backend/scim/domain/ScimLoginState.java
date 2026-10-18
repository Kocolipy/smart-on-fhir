package com.example.backend.scim.domain;

import java.time.Instant;

/**
 * A User's authentication state: the stored credential, the run of consecutive
 * failed logins, and the instant a lock was imposed.
 *
 * <p>Grouped into a record of its own for the reason {@link ScimUserProfile} is: this
 * is exactly the set the login path writes, and the profile is exactly the set a SCIM
 * replacement writes. Keeping them apart is what lets each be written as one
 * assignment, and is what makes "a failed login does not change the resource's
 * representation" expressible — none of these three components is a SCIM attribute,
 * so a write here does not bump the version or {@code meta.lastModified}.
 *
 * <p>{@code passwordHash} is nullable, and that is a supported state rather than an
 * incomplete one: SCIM allows a User to be created with no {@code password}, and such
 * a User exists, can be read, can be put in a Group and cannot log in. It is never a
 * plaintext value — the create use case hashes before this record is built, so there
 * is no constructor here that could hold one even briefly.
 *
 * <p>{@code lockedAt} records <em>when</em> the lock was imposed and nothing about
 * when it ends, because it does not end on its own: there is no duration, no expiry
 * and no clock in the decision. That is what makes {@link #isLocked()} a question
 * about this value alone — a lock cannot be "in the past", so no caller has to agree
 * with the server about the time to agree about the state.
 *
 * <p>{@code lastAuthenticatedAt} is the dormancy basis: when the User last used the account —
 * a successful login while no password change was required of it, or a completed self-service
 * change — or was last explicitly reactivated or unlocked. A login confined by a required change
 * does not move it, so an imposed credential nobody replaces still ages into the dormancy lockout.
 * Every one of those moves is the one transition {@link #withDormancyBasisReset}, and like the
 * other components the basis is not a SCIM attribute — recording a login does not move the
 * version.
 *
 * <p>The login-state module writes a transition as what it changed: it hands
 * {@link ScimLoginStateRepository#writeChange} the state it read and the state a transition below
 * decided, and the store writes the columns that differ. A directory create or replacement stores
 * the state the module decided for it with the profile, in the row write.
 *
 * <p>The transitions below are the login state's building blocks, not its rules: which of them an
 * event applies, and when, is the login-state module's to decide ({@link ScimUserLoginState} is
 * how the directory asks it). Every one has a production caller in that module, and none is kept
 * for a test alone.
 *
 * @param passwordHash         the stored credential, or {@code null} for a
 *                             credentialless User
 * @param failedLoginAttempts  consecutive rejected attempts, never negative
 * <p>{@code lockCause} says why the lock was imposed — a failure run or dormancy — and is
 * present exactly when {@code lockedAt} is: the two are set together and cleared together, and a
 * state where they disagree cannot be built.
 *
 * @param lockedAt             when a lock was imposed, or {@code null} when none is
 * @param lockCause            why the lock was imposed, or {@code null} exactly when
 *                             {@code lockedAt} is
 * @param lastAuthenticatedAt  when the User last authenticated, was reactivated or was
 *                             unlocked, or {@code null} when none of those has happened
 * @param passwordChangeRequiredSince when a password change was required of the User, or
 *                             {@code null} when none is — the change-required flag. Present when
 *                             the current credential was imposed on the User by somebody else (a
 *                             connector, an Admin forcing a change or lifting a lockout); its
 *                             value is when the change was last required. A presence
 *                             rather than a boolean plus a timestamp, as the lock is, so the two
 *                             cannot disagree. Written as part of a login-state change
 *                             ({@link ScimLoginStateRepository#writeChange}), cleared by a
 *                             completed password change, and stored by a SCIM create or
 *                             replacement storing the module's decision; not a SCIM attribute.
 */
public record ScimLoginState(
        String passwordHash,
        int failedLoginAttempts,
        Instant lockedAt,
        LockCause lockCause,
        Instant lastAuthenticatedAt,
        Instant passwordChangeRequiredSince) {

    /** A User that cannot authenticate and has no history: what a SCIM create yields. */
    public static final ScimLoginState CREDENTIALLESS =
            new ScimLoginState(null, 0, null, null, null, null);

    public ScimLoginState {
        if (failedLoginAttempts < 0) {
            throw new IllegalArgumentException("a failure run cannot be negative");
        }
        if ((lockedAt == null) != (lockCause == null)) {
            throw new IllegalArgumentException("a lock and its cause are set and cleared together");
        }
    }

    /**
     * A state whose lock, if any, came from a failure run — the shape every caller predating the
     * cause builds, and the only cause a lock had before dormancy could impose one.
     */
    public ScimLoginState(
            String passwordHash,
            int failedLoginAttempts,
            Instant lockedAt,
            Instant lastAuthenticatedAt,
            Instant passwordChangeRequiredSince) {
        this(passwordHash, failedLoginAttempts, lockedAt,
                lockedAt == null ? null : LockCause.FAILURES,
                lastAuthenticatedAt, passwordChangeRequiredSince);
    }

    /**
     * A state with no recorded authentication — the shape every caller that reasons only about
     * the credential and the failure run builds.
     */
    public ScimLoginState(String passwordHash, int failedLoginAttempts, Instant lockedAt) {
        this(passwordHash, failedLoginAttempts, lockedAt, null, null);
    }

    /** A state with no change required — the shape every caller predating the flag builds. */
    public ScimLoginState(
            String passwordHash,
            int failedLoginAttempts,
            Instant lockedAt,
            Instant lastAuthenticatedAt) {
        this(passwordHash, failedLoginAttempts, lockedAt, lastAuthenticatedAt, null);
    }

    /**
     * A User with a credential and no login history.
     *
     * <p>Takes the hash rather than the password: hashing happens in the use case that
     * received the plaintext, which is what "hashed immediately" means in practice.
     */
    public static ScimLoginState of(String passwordHash) {
        return passwordHash == null ? CREDENTIALLESS : new ScimLoginState(passwordHash, 0, null);
    }

    /**
     * Whether a credential is configured. A boolean rather than exposure of the hash:
     * whether a User can authenticate at all is an operational question an
     * administrative projection answers, and answering it does not require the value.
     */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    /**
     * Whether the User is closed to logins by a lockout. A lock stands until an
     * administrator lifts it, so the recorded instant being present <em>is</em> the
     * state: nothing is compared to a clock, and no passage of time changes the answer.
     */
    public boolean isLocked() {
        return lockedAt != null;
    }

    /**
     * Whether the User must replace its password before it may do anything but submit that change
     * or log out. As with {@link #isLocked()}, the recorded instant being present is the state.
     */
    public boolean isPasswordChangeRequired() {
        return passwordChangeRequiredSince != null;
    }

    /**
     * The state after one rejected login attempt.
     *
     * <p>A locked User is returned unchanged: the lock is already in force, so attempts
     * made against it neither count nor deepen it. Otherwise the failure is counted, and
     * reaching the policy's limit locks the User as of {@code now} — permanently, in the
     * sense that no later call here and no elapsed time lifts it. Only
     * {@link #withFailureRunCleared()} does.
     */
    public ScimLoginState withFailureRecorded(LockoutPolicy policy, Instant now) {
        if (isLocked()) {
            return this;
        }
        int attempts = failedLoginAttempts + 1;
        boolean locks = attempts >= policy.maxAttempts();
        return new ScimLoginState(
                passwordHash,
                attempts,
                locks ? now : null,
                locks ? LockCause.FAILURES : null,
                lastAuthenticatedAt,
                passwordChangeRequiredSince);
    }

    /**
     * The state after the dormancy job found the User past the lockout window, as the
     * login-state module decides it: locked as of {@code now} with {@link LockCause#DORMANCY}. A User already locked is returned unchanged,
     * so the cause it was first locked for is the one the record keeps. The failure run is left
     * as it stands — dormancy is not a failed attempt.
     */
    public ScimLoginState withDormancyLock(Instant now) {
        if (isLocked()) {
            return this;
        }
        return new ScimLoginState(
                passwordHash,
                failedLoginAttempts,
                now,
                LockCause.DORMANCY,
                lastAuthenticatedAt,
                passwordChangeRequiredSince);
    }

    /**
     * The state after one rejected login attempt that must never lock the User: the
     * failure run lengthens and no lock is imposed, whatever the policy's limit says.
     *
     * <p>This is the Bootstrap Admin's path, and the reason it is a transition of its own
     * rather than a flag on {@link #withFailureRecorded}: with no automatic lift, a
     * locked recovery identity is an unrecoverable deployment, so "counted but never
     * locked" is a distinct rule and is named as one. The run is still counted because
     * the failures are still evidence — the audit trail records each of them either way.
     */
    public ScimLoginState withFailureCounted() {
        return new ScimLoginState(
                passwordHash,
                failedLoginAttempts + 1,
                lockedAt,
                lockCause,
                lastAuthenticatedAt,
                passwordChangeRequiredSince);
    }

    /**
     * The state with no failures recorded and no lockout standing.
     *
     * <p>Two callers reach it for unrelated reasons — an accepted login, and an
     * administrator lifting a lockout — and it is one method because "no failures, no
     * lock" is one state. A value with nothing to clear is returned as-is, so a caller
     * can use the identity of the result to avoid a pointless write.
     *
     * <p>A locked User never reaches here by the login route: it is refused before its
     * password is compared. So on that path this clears a run rather than a lock.
     */
    public ScimLoginState withFailureRunCleared() {
        if (failedLoginAttempts == 0 && lockedAt == null) {
            return this;
        }
        return new ScimLoginState(
                passwordHash, 0, null, null, lastAuthenticatedAt, passwordChangeRequiredSince);
    }

    /**
     * The state holding {@code passwordHash} as its credential — {@code null} for none — and
     * everything else as it was. What a directory write that sets, keeps or removes the password
     * starts from; whether the new credential owes a change is the login-state module's to decide.
     */
    public ScimLoginState withCredential(String passwordHash) {
        return new ScimLoginState(
                passwordHash, failedLoginAttempts, lockedAt, lockCause, lastAuthenticatedAt,
                passwordChangeRequiredSince);
    }

    /**
     * The state with a password change required as of {@code since}. Re-dated rather than kept
     * when already set: each imposed credential is a new one the User did not choose, and the flag
     * records when the credential it must replace was imposed.
     */
    public ScimLoginState withPasswordChangeRequired(Instant since) {
        if (since == null) {
            throw new IllegalArgumentException("a required change has an instant");
        }
        return new ScimLoginState(
                passwordHash, failedLoginAttempts, lockedAt, lockCause, lastAuthenticatedAt, since);
    }

    /**
     * The state with its dormancy basis restarted at {@code now}: the one transition every move of
     * the basis is. A use of the account — an unconfined Login, a completed self-service change —
     * restarts the window because the account is in use (ADR 0008); an Unlock and a Reactivation
     * restart it because a User an administrator or the directory has just brought back starts a
     * fresh window, so the job does not lock it again on its next run (ADR 0007). Everything else
     * is kept.
     */
    public ScimLoginState withDormancyBasisReset(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("a restarted basis has an instant");
        }
        return new ScimLoginState(
                passwordHash, failedLoginAttempts, lockedAt, lockCause, now,
                passwordChangeRequiredSince);
    }
}
