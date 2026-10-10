package com.example.backend.audit.domain;

/**
 * Why a {@code USER_SESSIONS_REVOKE} event's sessions were ended, as its reason records it. The
 * audit trail's own closed set, mirroring the login surface's Session revocation causes one for
 * one and by the same names, without depending on the module that owns them — as
 * {@link AuditLockCause} mirrors the directory's lock causes.
 */
public enum AuditSessionRevocationCause {

    /** A connector's write took {@code active} from true to false. */
    DEACTIVATED,

    /** The password was set, changed or removed — by a connector, or by its own holder. */
    PASSWORD_CHANGED,

    /** A connector's write changed {@code userName}. */
    USER_NAME_CHANGED,

    /** A connector deleted the User. */
    DELETED,

    /** The User lost a Role, by a connector's write or the dormancy job. */
    ROLE_REVOKED,

    /** The dormancy job locked the User. */
    DORMANCY_LOCKOUT,

    /** The failure run reached the lockout policy's limit. */
    FAILURE_RUN_LOCKOUT,

    /** An administrator required the User to change its password. */
    FORCED_PASSWORD_CHANGE,

    /** An accepted Login ended every other session the User held — one session per User. */
    REPLACED_BY_LOGIN
}
