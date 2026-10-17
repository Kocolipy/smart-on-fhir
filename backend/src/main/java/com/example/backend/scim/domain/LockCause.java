package com.example.backend.scim.domain;

/**
 * Why a User is locked. Recorded beside the lock instant, set with it and cleared with it, so a
 * helpdesk operator can tell a forgotten password from an abandoned account before unlocking
 * (ADR 0007).
 *
 * <p>The cause changes nothing about the lock itself: either lock closes the account to logins
 * until an administrator's Unlock, the login refusal is the same bare {@code 401} for both, and a
 * lock that is already standing keeps the cause it was imposed with.
 */
public enum LockCause {

    /** The failure run reached the lockout policy's limit (ADR 0007). */
    FAILURES,

    /** The dormancy job found the User past the lockout window without authenticating. */
    DORMANCY
}
