package com.example.backend.auth.domain;

/**
 * Why a Session revocation ended a User's sessions — the one vocabulary every trigger is named in,
 * by the audit event that records the revocation and by the session-end log alike.
 *
 * <p>Each is a change a live session must not outlast. The directory names the subset its writes
 * can cause in its own port ({@code ScimUserSessions.Cause}), because the dependency between the
 * two slices runs from here to the directory and never back; the login surface maps those onto
 * these, one for one and by the same names.
 */
public enum SessionRevocationCause {

    /** A connector's write took {@code active} from true to false. */
    DEACTIVATED,

    /**
     * The password was set, changed or removed — by a connector's write, or by the User's own
     * self-service change.
     */
    PASSWORD_CHANGED,

    /** A connector's write changed {@code userName}. */
    USER_NAME_CHANGED,

    /** A connector deleted the User. */
    DELETED,

    /**
     * The User lost a Role: its direct membership of a mapped Group was removed, by a connector's
     * write or the dormancy job. Gaining one revokes nothing — the new Permissions arrive at the
     * next Login.
     */
    ROLE_REVOKED,

    /** The dormancy job locked the User. */
    DORMANCY_LOCKOUT,

    /**
     * The User's failure run reached the lockout policy's limit — from a rejected Login or a
     * rejected self-service password change. The only cause imposed by a request that is itself
     * being refused.
     */
    FAILURE_RUN_LOCKOUT,

    /** An administrator required the User to change its password. */
    FORCED_PASSWORD_CHANGE,

    /**
     * An accepted Login ended every other session the User held — one session per User. The
     * session the Login continues in is the one kept.
     */
    REPLACED_BY_LOGIN
}
