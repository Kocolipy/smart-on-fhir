package com.example.backend.audit.domain;

/**
 * What an audit event says happened, as a closed set.
 *
 * <p>A closed set rather than a string because the operation is the field every
 * later query groups by, and because a free-text operation is a place a caller's
 * value could be written. Every member below corresponds to a behaviour this
 * service already has; nothing is added here in anticipation of one.
 */
public enum AuditOperation {

    /** Submitted credentials were accepted and a session was issued. */
    LOGIN_SUCCESS,

    /**
     * Submitted credentials were refused — wrong password, unknown username,
     * locked account, disabled account. Which of those is carried as the event's
     * error code, from {@link AuditRefusalReason}.
     */
    LOGIN_FAILURE,

    /** A session was ended by its holder. */
    LOGOUT,

    /**
     * A lock was imposed: by a failure run reaching the configured limit. A lock the dormancy job
     * imposes is {@link #DORMANCY_LOCKOUT} instead.
     */
    LOCKOUT_SET,

    /**
     * An administrator lifted a lockout. A lockout has no duration, so it never ends
     * unrequested, and every event of this operation names the administrator who ended it. Its
     * {@code errorCode} carries the cause of the lock it lifted — {@code FAILURES} or
     * {@code DORMANCY}. An Unlock of a User serving no lockout is refused and recorded as a refusal
     * ({@code NOT_LOCKED}).
     */
    LOCKOUT_LIFT,

    /** An administrator created a SCIM connector. */
    CONNECTOR_CREATE,

    /**
     * An administrator deleted a SCIM connector, which revoked every token it held
     * and removed every {@code externalId} alias it owned.
     */
    CONNECTOR_DELETE,

    /** An administrator minted a new token for a connector. */
    CONNECTOR_TOKEN_ISSUE,

    /**
     * An administrator replaced a connector's token, shortening the old one's life
     * to the overlap window.
     */
    CONNECTOR_TOKEN_ROTATE,

    /**
     * A connector token stopped being accepted because an administrator said so —
     * individually, or as part of deleting the connector.
     */
    CONNECTOR_TOKEN_REVOKE,

    /**
     * A connector created a SCIM User, or was refused one. The outcome tells the two
     * apart, and a refusal carries its reason as the event's error code, from
     * {@link AuditScimRefusal} — a create a connector kept retrying against a
     * {@code userName} that already exists is a broken integration, and it is only
     * visible in the trail if the refusals are recorded beside the successes.
     */
    SCIM_USER_CREATE,

    /**
     * A connector queried the User collection — {@code GET /Users} or
     * {@code POST /Users/.search} — a bulk read, whatever it asked for and whatever came
     * back. The event carries how many resources the response held and the filter's shape.
     *
     * <p>Recorded for an empty result and for a single-resource page alike, because
     * what the event exists to make visible is a credential enumerating the
     * directory, and an enumeration that finds nothing is the same act as one that
     * finds everything. Retrieving ONE User by its id is not this operation and is
     * not recorded at all: it is the ordinary unit of provisioning traffic, and
     * recording it would bury the reads that matter under the reads that do not.
     */
    SCIM_USER_LIST,

    /**
     * A connector replaced a User's attributes (PUT) or patched them (PATCH), or was refused.
     * One operation for both verbs, as {@link #SCIM_GROUP_REPLACE} is: the stored change is the
     * same, and which attributes moved is carried as the event's changed paths — {@code password}
     * among them when the credential changed, never its value. A refusal carries its reason from
     * {@link AuditScimRefusal}; a reused password is {@code INVALID_VALUE}.
     */
    SCIM_USER_REPLACE,

    /**
     * A connector deleted a User, or was refused — the Bootstrap Admin cannot be deleted. The
     * event names the User by its stable id, which is what keeps the trail about this identity
     * readable after its {@code userName} has been reused by another. The sessions the deletion
     * ended are recorded after the commit as {@link #USER_SESSIONS_REVOKE}.
     */
    SCIM_USER_DELETE,

    /**
     * A User's sessions were ended after a committed write changed something they were issued
     * against — deactivation, a password change, a {@code userName} change, or the User's
     * deletion. Recorded after the
     * commit, as its own event, because that is when the revocation happens and only then is its
     * outcome known: {@code SUCCESS} when the session store ended them, {@code FAILURE} when it
     * could not, in which case the write stands and its sessions survive. The changed paths name
     * the attributes whose change caused it.
     */
    USER_SESSIONS_REVOKE,

    /**
     * A connector created a SCIM Group, or was refused one. The outcome tells the two
     * apart, and a refusal carries its reason as the event's error code, from
     * {@link AuditScimRefusal} — a create refused because a member id names no live User
     * is a broken integration, and it is only visible in the trail if the refusals are
     * recorded beside the successes.
     */
    SCIM_GROUP_CREATE,

    /**
     * A connector replaced a Group's attributes or membership, or was refused. One
     * operation for PUT and PATCH alike, because the stored change is the same and the
     * distinction a reader cares about is which attributes moved — which the event's
     * changed paths carry.
     *
     * <p>A refused rename of the Admin group, and a refused removal of the Bootstrap
     * Admin's membership from it, are both this operation with a {@code FAILURE} outcome
     * and {@link AuditScimRefusal#MUTABILITY}. That is deliberate: an attempt to
     * provision the deployment's recovery authority away is exactly what an administrator
     * reading the trail needs to see, and it is invisible if a refusal records nothing.
     */
    SCIM_GROUP_REPLACE,

    /** A connector deleted a Group, or was refused — the Admin group cannot be deleted. */
    SCIM_GROUP_DELETE,

    /**
     * A connector read the Group collection — a bulk read, whatever it asked for and
     * whatever came back, for the reason {@link #SCIM_USER_LIST} is recorded that way.
     * Retrieving ONE Group by its id is not this operation and is not recorded.
     */
    SCIM_GROUP_LIST,

    /**
     * A connector searched Users and Groups together through the base {@code /.search}
     * endpoint — one bulk read spanning both types, recorded once, for the reason
     * {@link #SCIM_USER_LIST} is recorded that way.
     */
    SCIM_RESOURCE_LIST,

    /**
     * The server created one of the resources it reserves for recovery — the Bootstrap
     * Admin, or the Admin group — on a database that did not have it.
     *
     * <p>Recorded with no actor, because there is none: seeding is the deployment
     * establishing its own recovery path at startup, not a principal acting. The event
     * exists because the appearance of an administrative identity is the single most
     * consequential thing that happens to this directory, and "it was always there" and
     * "it was created at 03:14 on a restart nobody expected" must be distinguishable.
     */
    SCIM_RESOURCE_SEED,

    /**
     * The dormancy job locked a User that had gone longer than the lockout window without
     * authenticating. Recorded with no actor, because the actor is the scheduled job rather than a
     * principal — the operation itself is what names it. The changed paths are the lock and its
     * cause; the sessions it ended are recorded after the commit as {@link #USER_SESSIONS_REVOKE},
     * also with no actor.
     */
    DORMANCY_LOCKOUT,

    /**
     * The dormancy job removed every direct Group membership of a User that had gone longer than
     * the revocation window without authenticating — the Admin group's included, the Bootstrap
     * Admin's exempt. Recorded against the affected USER, with no actor, the changed path
     * {@code groups}, every removed Group by id, and the User's effective Permissions before and
     * after with the Superuser marker: one event per User, which is what an unexpected mass removal
     * is reversed by hand from.
     */
    DORMANCY_MEMBERSHIP_REVOCATION,

    /**
     * A password change was required of a User by an administrator lifting its lockout, which
     * requires one because the credential that reached the threshold may be the one being guessed.
     * The event never carries a password value; the changed path is the flag.
     */
    PASSWORD_CHANGE_REQUIRE,

    /**
     * A User submitted a self-service password change, accepted or refused. Neither the current nor
     * the new value is ever recorded: an accepted change records the changed paths, a refusal its
     * reason from {@link AuditPasswordChangeRefusal}.
     */
    PASSWORD_CHANGE,

    /**
     * A signed-in caller was refused an operation on the application chain, for want of the
     * Permission it requires, because no rule declares it, or because the session is confined
     * by a required password change. The actor and subject are the refused User; the event
     * carries the operation as its method and route template, and the one generic reason
     * {@code INSUFFICIENT_PERMISSIONS} as its error code. It never names the Permission or rule
     * that refused — each operation requires exactly one Permission, which its declaration
     * in the API document names.
     */
    ACCESS_DENIED,

    /**
     * A connector's write added a User to a Group that confers Permissions, or to the Admin group.
     * Recorded against the USER, with the connector as the actor, the Group as the resource, and
     * the User's effective Permissions before and after with the Superuser marker — a membership
     * change of such a Group is a change of power, and the event says which. The User's live
     * sessions are untouched: it holds the Permissions from its next sign-in. A Group that confers
     * nothing records no such event.
     */
    PERMISSION_GRANT,

    /**
     * A User lost direct membership of a Group that confers Permissions, or of the Admin group: a
     * connector's write removed it, by PATCH, PUT or the Group's DELETE. Recorded like
     * {@link #PERMISSION_GRANT}; the User's sessions are then revoked after the commit, recorded as
     * {@link #USER_SESSIONS_REVOKE}.
     */
    PERMISSION_REVOKE,

    /**
     * A Superuser replaced a Group's Permissions, or was refused (ADR 0015). Recorded against the
     * GROUP, as subject and resource, with the Superuser as the actor and the Group's Permissions
     * before and after, sorted by name; the changed path is {@code permissions}. A refusal — a body
     * that is not a set of delegatable Permissions, a Group that does not exist, the Admin group —
     * names no Permission, only its generic reason from {@link AuditAdministrativeRefusal}. When
     * the change took a Permission away, every direct member's sessions are then revoked after the
     * commit, recorded as {@link #USER_SESSIONS_REVOKE}.
     */
    GROUP_PERMISSIONS_CHANGE,

    /**
     * A signed-in User asked for its Epic session's SMART access token, for the SPA to connect to
     * the FHIRcast hub (ADR 0016), and was given it or refused. Recorded against the USER, as actor
     * and subject. A release is fail-closed: a token this service cannot account for is not
     * released. A refusal carries its reason from {@link AuditFhircastRefusal}. Neither ever
     * carries the token's value.
     */
    FHIRCAST_TOKEN_RELEASE
}
