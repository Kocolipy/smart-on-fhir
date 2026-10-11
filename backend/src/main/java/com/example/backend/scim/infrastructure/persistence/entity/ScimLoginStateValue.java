package com.example.backend.scim.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

/**
 * Database representation of a User's authentication state: the stored credential, the
 * run of consecutive failed logins, and the instant a lock was imposed.
 *
 * <p>Embedded rather than three loose columns on {@link ScimUserEntity}, mirroring the
 * domain's {@code ScimLoginState}. The grouping is what makes "the login path writes
 * these and only these" visible in the mapping as well as in the port, and it keeps the
 * User entity's constructor from growing a third and fourth parameter that read as
 * profile attributes but are not.
 *
 * <p>None of the three is a SCIM attribute, so writing them changes nothing a client can
 * read — which is why the port's login-state write deliberately does not advance the
 * resource's version or {@code lastModified}.
 */
@Embeddable
public class ScimLoginStateValue {

    /** Nullable: a credentialless User is a supported state, not an incomplete one. */
    @Column(name = "password_hash", length = 256)
    private String passwordHash;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    /**
     * When a lock was imposed, or null when none is.
     *
     * <p>Not when a lock ENDS: it does not end on its own. A lock is lifted by an
     * administrator's Unlock and by nothing else, so the column's presence is the whole
     * state and no clock is consulted to read it.
     */
    @Column(name = "locked_at")
    private Instant lockedAt;

    /**
     * Why the lock was imposed — {@code FAILURES} or {@code DORMANCY}, the names of
     * {@code LockCause} — or null exactly when {@link #lockedAt} is. Stored as the name rather than
     * mapped as an enum so the domain type stays free of persistence annotations; the adapter
     * converts it.
     */
    @Column(name = "lock_cause", length = 16)
    private String lockCause;

    /**
     * When the User last authenticated, was reactivated or was unlocked, or null when none of
     * those has happened — the dormancy basis, with the resource's creation time as the fallback.
     */
    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    /**
     * When a password change was required of the User, or null when none is — the
     * change-required flag. Written by its own narrow statements, and by a SCIM replacement
     * storing the value it was given.
     */
    @Column(name = "password_change_required_since")
    private Instant passwordChangeRequiredSince;

    protected ScimLoginStateValue() {
    }

    public ScimLoginStateValue(
            String passwordHash,
            int failedLoginAttempts,
            Instant lockedAt,
            String lockCause,
            Instant lastAuthenticatedAt,
            Instant passwordChangeRequiredSince) {
        this.passwordHash = passwordHash;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockedAt = lockedAt;
        this.lockCause = lockCause;
        this.lastAuthenticatedAt = lastAuthenticatedAt;
        this.passwordChangeRequiredSince = passwordChangeRequiredSince;
    }

    public Instant getPasswordChangeRequiredSince() {
        return passwordChangeRequiredSince;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public String getLockCause() {
        return lockCause;
    }

    public Instant getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    /**
     * Assigns what a SCIM replacement stores of the login state — the credential, the
     * change-required flag and the dormancy basis — as given. The values are decided before they
     * reach here; this holds no rule about them. Assigning a value equal to the one loaded leaves
     * its column clean, so only what the write changed is written. The failure run and the lock
     * have no mutator: the login path writes them through its own narrow statements.
     */
    public void storeDirectoryLoginState(
            String passwordHash, Instant passwordChangeRequiredSince, Instant lastAuthenticatedAt) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequiredSince = passwordChangeRequiredSince;
        this.lastAuthenticatedAt = lastAuthenticatedAt;
    }
}
