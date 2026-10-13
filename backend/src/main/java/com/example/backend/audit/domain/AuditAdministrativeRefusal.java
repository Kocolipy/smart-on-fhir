package com.example.backend.audit.domain;

/**
 * Why an administrative change was refused, as a closed set.
 *
 * <p>A closed set for the reason {@link AuditRefusalReason} is one: the reason is a field
 * every later query groups by, and a free-text reason is a place a caller's own value could
 * be written. These are refusals about the ACTION rather than about the caller — the caller
 * is an authenticated administrator in every case — so none of them is an authorization
 * failure and none is recorded as one.
 *
 * <p>Refusals are recorded and not merely logged because each of them is an attempt to
 * remove the deployment's last way back in. A run of them is the signal worth having, and a
 * log line that carries no subject id cannot be correlated to the identity it was about.
 */
public enum AuditAdministrativeRefusal {

    /**
     * An administrator tried to disable their own identity, which would take away the
     * access needed to reverse it. No longer recorded — the Disable action was removed — and
     * kept so the refusals already stored still read back.
     */
    SELF_DISABLE,

    /**
     * The target is the Bootstrap Admin, the deployment's recovery identity, and the operation
     * is one nobody may perform on it — today, a forced password change. Also stored, from
     * before the Disable action was removed, for a refused attempt to disable it.
     */
    PROTECTED_RESOURCE,

    /**
     * An administrator aimed an Unlock or a forced password change at their own account. Refused so
     * that recovering from a self-inflicted state takes a second administrator.
     */
    SELF_TARGET,

    /**
     * A forced password change aimed at a User with no credential, which already cannot log in and
     * has no password to replace.
     */
    CREDENTIALLESS_TARGET,

    /**
     * A connector token issue or rotation asking for a Permission the administrator does not hold
     * itself. Refused so that {@code connector:token} cannot mint a credential more powerful than
     * whoever holds it; the event names the Permissions that were asked for.
     */
    PERMISSION_ESCALATION,

    /** A connector token rotation addressing a token that has been revoked. */
    TOKEN_REVOKED,

    /**
     * A connector token rotation addressing a token that has already been rotated — the loser of
     * two concurrent rotations of one token included. Only one replacement may ever be live.
     */
    TOKEN_ALREADY_ROTATED,

    /** A connector token rotation addressing a token whose expiry has passed. */
    TOKEN_EXPIRED
}
