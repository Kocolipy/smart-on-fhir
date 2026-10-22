package com.example.backend.audit.domain;

/**
 * Why a {@code USER_SESSIONS_REVOKE} event's sessions were ended, as its reason records it. The
 * audit trail's own closed set, mirroring the login surface's Session revocation causes by the
 * same names, without depending on the module that owns them — as {@link AuditLockCause} mirrors
 * the directory's lock causes. It holds one cause the login surface no longer raises,
 * {@link #FORCED_PASSWORD_CHANGE}, kept for the events already stored.
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

    /**
     * The User lost a Group membership, by a connector's write or the dormancy job. Rows recorded
     * before #158 carry it under its earlier name, {@code ROLE_REVOKED}: the trail is append-only
     * and nothing reads the reason back, so they are left as written.
     */
    MEMBERSHIP_REVOKED,

    /** A Superuser took a Permission away from a Group the User is a direct member of. */
    GROUP_PERMISSIONS_REDUCED,

    /** The dormancy job locked the User. */
    DORMANCY_LOCKOUT,

    /** The failure run reached the lockout policy's limit. */
    FAILURE_RUN_LOCKOUT,

    /**
     * An administrator required the User to change its password. No longer recorded — the forced
     * password change was removed (ADR 0015's 2026-10-22 addendum) — and kept so the revocations
     * already stored under it are still named in this vocabulary.
     */
    FORCED_PASSWORD_CHANGE,

    /** An accepted Login ended every other session the User held — one session per User. */
    REPLACED_BY_LOGIN
}
