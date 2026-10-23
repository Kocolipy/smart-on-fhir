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
 * <p>Refusals are recorded and not merely logged because a run of them is the signal worth
 * having, and a log line that carries no subject id cannot be correlated to the identity it was
 * about.
 */
public enum AuditAdministrativeRefusal {

    /**
     * The target is a resource the operation may not touch: the Admin group, whose direct members'
     * power is the Superuser rather than a Permission, for a change of a Group's Permissions.
     */
    PROTECTED_RESOURCE,

    /**
     * An administrator aimed an Unlock at their own account. Refused so that recovering from a
     * self-inflicted state takes a second administrator.
     */
    SELF_TARGET,

    /**
     * An Unlock aimed at a User serving no lockout: there was nothing to lift, so nothing was
     * written, its failure run included (ADR 0015).
     */
    NOT_LOCKED,

    /** A connector token rotation addressing a token that has been revoked. */
    TOKEN_REVOKED,

    /**
     * A connector token rotation addressing a token that has already been rotated — the loser of
     * two concurrent rotations of one token included. Only one replacement may ever be live.
     */
    TOKEN_ALREADY_ROTATED,

    /** A connector token rotation addressing a token whose expiry has passed. */
    TOKEN_EXPIRED,

    /**
     * A change of a Group's Permissions whose body was not a set of Permissions a Group may carry:
     * missing, not an array, or naming something that is not a delegatable Permission. Which value
     * it was is never recorded.
     */
    INVALID_PERMISSIONS,

    /**
     * An administrative operation addressing something that does not exist, or no longer does: an
     * Unlock's User, a Group whose Permissions were to be set, a connector to delete or to issue a
     * token for, or a token to rotate or revoke — a token whose connector was deleted among them.
     * The id addressed is recorded, though it names nothing.
     */
    UNKNOWN_RESOURCE
}
