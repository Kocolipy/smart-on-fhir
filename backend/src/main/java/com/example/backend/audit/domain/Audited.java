package com.example.backend.audit.domain;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Something that happened, as a value the {@link AuditTrail} appends: one record per kind of
 * event this service records, each carrying its operation, its outcome and the paths it changed.
 *
 * <p><strong>No component of any event is a {@link String}.</strong> That is the redaction rule
 * expressed as a shape rather than as a review note: a username, a password and a bearer value are
 * all strings, so an event that holds none cannot be handed one by a caller who never read the
 * rule. Every reference to a person or a resource is a stable id, and every classification is a
 * member of a closed set. The recorded text — the resource type, the path names, the status class,
 * the error code — is spelled here, from vocabularies this slice owns, which is why the events live
 * in it: a caller that assembled those strings could assemble anything.
 * {@code ArchitectureTest.the_audit_trail_boundary_admits_no_free_text} holds the shape, and
 * {@code semgrep/rules/service-security.yml} flags an event constructed with a credential-named
 * value.
 *
 * <h2>Which failure semantics an event gets</h2>
 *
 * <p>Every event is one of three kinds, named for what the triggering request was going to
 * return — the axis {@code /docs/adr/0004-audit-append-failure-semantics.md} decides on:
 *
 * <ul>
 *   <li>{@link Accepted}: the request would otherwise succeed, so the append is
 *       <strong>fail-closed</strong> — it joins the caller's transaction, and a change this service
 *       cannot account for does not happen. A bulk read is one: the honest outcome of an append
 *       that cannot commit is an error, not a directory handed over and forgotten.
 *   <li>{@link Refused}: the request is already being refused, so the append is
 *       <strong>fail-open with an alert</strong> — in a transaction of its own, so the refusal's
 *       answer does not change. A Lockout imposed by a rejected Login is one, though its outcome
 *       is a success: it rides the refusal.
 *   <li>{@link Committed}: recorded after the triggering change committed, so there is nothing
 *       left for a failed append to undo — fail-open with an alert, whatever its outcome.
 * </ul>
 *
 * <p>The kind is the event's type, so a caller says what happened and never chooses whether the
 * trail may fail silently; {@code AuditTrailService} reads the kind and applies the rule once.
 */
public sealed interface Audited {

    /**
     * The path list of an event that changed nothing — a read, a refusal, a creation recorded as
     * the appearance of a whole resource. One constant rather than an empty list built at each
     * event, so "nothing changed" is spelled once.
     */
    List<String> NO_PATHS = List.of();

    /**
     * Whether the append is fail-closed: {@code true} for an {@link Accepted} event, {@code false}
     * for a {@link Refused} or {@link Committed} one. Declared by the three kinds, not by the
     * events, so an event's semantics are its kind's.
     */
    boolean failsClosed();

    /**
     * The stored row this event is, stamped with what the trail supplies.
     *
     * @param stamp the event's own identity, when it happened and the request being served
     */
    AuditEvent toEvent(Stamp stamp);

    /**
     * What the trail supplies to every event: an identity, an instant and the request context.
     * The context rather than its current value, so an event that carries its own request — an
     * authorization refusal, which predates the route match — reads none.
     */
    record Stamp(UUID id, Instant occurredAt, AuditRequestContext requests) {
    }

    /** An event of a request that would otherwise succeed: appended fail-closed. */
    sealed interface Accepted extends Audited {

        @Override
        default boolean failsClosed() {
            return true;
        }
    }

    /** An event of a request already being refused: appended fail-open, with an alert. */
    sealed interface Refused extends Audited {

        @Override
        default boolean failsClosed() {
            return false;
        }
    }

    /** An event recorded after its change committed: appended fail-open, with an alert. */
    sealed interface Committed extends Audited {

        @Override
        default boolean failsClosed() {
            return false;
        }
    }

    // ---- Login, Logout and Lockout -----------------------------------------------------------

    /**
     * An accepted login. A session this service cannot account for is not issued.
     *
     * @param method how the Login proved who was signing in (D15)
     * @param factor the MFA factor an Epic Login was made with (D17), or {@code null} for a Login
     *               that carries none
     */
    record LoginSuccess(UUID accountId, AuditLoginMethod method, AuditMfaFactor factor)
            implements Accepted {

        /** An accepted login that carries no MFA factor: a password Login's. */
        public LoginSuccess(UUID accountId, AuditLoginMethod method) {
            this(accountId, method, null);
        }

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.login(stamp,
                    AuditRows.Change.success(AuditOperation.LOGIN_SUCCESS,
                            accountId, accountId, NO_PATHS),
                    method, factor);
        }
    }

    /**
     * A refused login that counted toward the failure run. The caller is already receiving a bare
     * {@code 401}, and that answer does not change.
     *
     * @param subjectId stable id of the account the attempt named, or {@code null} when the
     *                  submitted username names no account — the one thing that must not be
     *                  recorded in its place
     * @param method    how the refused Login was attempted (D15)
     */
    record LoginFailure(UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method)
            implements Refused {

        /** A rejected login lengthens the failure run and nothing else. */
        public static final List<String> PATHS = List.of("failedLoginAttempts");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.loginFailure(stamp, subjectId, reason, method, PATHS);
        }
    }

    /**
     * A refused login that counted toward no failure run — an Epic Login refused by the login
     * decision (D12), whose credential Epic checked rather than this service. The same
     * {@code LOGIN_FAILURE} as {@link LoginFailure}, naming no changed path, because nothing about
     * the account changed.
     *
     * @param subjectId stable id of the account refused, or {@code null} when the attempt named no
     *                  acceptable account, so that no subject is recorded
     */
    record LoginRefusal(UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.loginFailure(stamp, subjectId, reason, method, NO_PATHS);
        }
    }

    /**
     * A session ended by its holder, recorded before the session is invalidated, so a logout this
     * service cannot account for does not happen.
     */
    record Logout(UUID accountId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.success(AuditOperation.LOGOUT,
                            accountId, accountId, NO_PATHS));
        }
    }

    /**
     * A failure run reaching the configured limit. A success — the lock was imposed — on a request
     * that is already a refusal, the rejected Login or password change it rides; so it is
     * {@link Refused}, and a trail outage cannot turn that {@code 401} into a {@code 500}.
     */
    record LockoutSet(UUID accountId) implements Refused {

        /** The lockout columns a failure lock writes. */
        public static final List<String> PATHS =
                List.of("failedLoginAttempts", "lockedAt", "lockCause");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp, new AuditRows.Change(AuditOperation.LOCKOUT_SET,
                    AuditOutcome.SUCCESS, null, accountId, PATHS, AuditEvent.STATUS_CLIENT_ERROR,
                    null));
        }
    }

    /**
     * An administrator's Unlock: the only lift there is. A lock does not expire, so there is no
     * unrequested lift to record, and {@code actorId} names the administrator who performed it.
     *
     * <p>The paths are what the Unlock actually changed. The failure run, when it held attempts;
     * and, when a lock stood, the lock and its cause together with the dormancy basis
     * ({@code lastAuthenticatedAt}) the lift restarts. An Unlock of a User serving no lockout
     * clears its run and nothing else. The change-required flag the lift may also set is recorded
     * by its own {@link PasswordChangeRequired}.
     *
     * @param lockCause       why the lifted lock had been imposed, recorded as the event's reason,
     *                        or {@code null} when the User was not locked
     * @param attemptsCleared whether the User's failure run held attempts the Unlock cleared
     */
    record LockoutLiftedByUnlock(
            UUID actorId, UUID subjectId, AuditLockCause lockCause, boolean attemptsCleared)
            implements Accepted {

        /** What clearing a failure run that held attempts writes. */
        public static final List<String> CLEARED_RUN_PATHS = List.of("failedLoginAttempts");

        /** What lifting a lock writes, beside the failure run: the lock, and the dormancy basis. */
        public static final List<String> LIFTED_PATHS =
                List.of("lockedAt", "lockCause", "lastAuthenticatedAt");

        /** The paths this Unlock changed, in the recorded order. */
        public List<String> changedPaths() {
            List<String> paths = new ArrayList<>();
            if (attemptsCleared) {
                paths.addAll(CLEARED_RUN_PATHS);
            }
            if (lockCause != null) {
                paths.addAll(LIFTED_PATHS);
            }
            return List.copyOf(paths);
        }

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp, new AuditRows.Change(AuditOperation.LOCKOUT_LIFT,
                    AuditOutcome.SUCCESS, actorId, subjectId, changedPaths(), AuditEvent.STATUS_OK,
                    lockCause == null ? null : lockCause.name()));
        }
    }

    /** An Unlock refused — of the administrator's own account. */
    record UnlockRefused(UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.refusal(AuditOperation.LOCKOUT_LIFT,
                            actorId, subjectId, reason));
        }
    }

    // ---- Connectors and their tokens ---------------------------------------------------------
    //
    // Every event here names the CONNECTOR by its id and never the token, and none can be handed a
    // token value: the plaintext is a String, and no event holds one. A connector or token change
    // is fail-closed for the reason the administrative account writes are: a credential this
    // service cannot say who minted is worse than a failed mint an administrator retries.

    /** A connector created. */
    record ConnectorCreated(UUID actorId, UUID connectorId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.connector(
                    stamp, AuditOperation.CONNECTOR_CREATE, actorId, connectorId, NO_PATHS);
        }
    }

    /** A connector deleted, along with the tokens and aliases that went with it. */
    record ConnectorDeleted(UUID actorId, UUID connectorId) implements Accepted {

        /** What deleting a connector changes on the connector row itself. */
        public static final List<String> PATHS = List.of("deletedAt");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.connector(
                    stamp, AuditOperation.CONNECTOR_DELETE, actorId, connectorId, PATHS);
        }
    }

    /**
     * A token minted for a connector, with the Permissions it carries — the question an
     * administrator reading the trail is asking of a credential.
     */
    record ConnectorTokenIssued(UUID actorId, UUID connectorId, Set<Permission> granted)
            implements Accepted {

        /**
         * A token's issue is the appearance of a whole row, so the path list names the two fields
         * that decide what the credential can do and for how long. It never names the digest.
         */
        public static final List<String> PATHS = List.of("permissions", "expiresAt");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.token(stamp, AuditRows.Change.success(
                    AuditOperation.CONNECTOR_TOKEN_ISSUE, actorId, connectorId, PATHS), granted);
        }
    }

    /**
     * A connector's token replaced, the old one ending at the overlap, with the Permissions the
     * replacement carries.
     */
    record ConnectorTokenRotated(UUID actorId, UUID connectorId, Set<Permission> granted)
            implements Accepted {

        /** Rotation shortens the old token and records which token replaced it. */
        public static final List<String> PATHS = List.of("expiresAt", "replacedByTokenId");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.token(stamp, AuditRows.Change.success(
                    AuditOperation.CONNECTOR_TOKEN_ROTATE, actorId, connectorId, PATHS), granted);
        }
    }

    /**
     * A token issue refused because the administrator asked for a Permission it does not hold
     * itself. Unlike an authorization refusal this one DOES name Permissions — the ones requested
     * — because an attempt to mint a credential more powerful than oneself is exactly what an
     * auditor looks for.
     *
     * @param requested every Permission the refused token would have carried
     */
    record ConnectorTokenIssueRefused(UUID actorId, UUID connectorId, Set<Permission> requested)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.token(stamp, AuditRows.Change.refusal(
                    AuditOperation.CONNECTOR_TOKEN_ISSUE, actorId, connectorId,
                    AuditAdministrativeRefusal.PERMISSION_ESCALATION), requested);
        }
    }

    /** A rotation refused for the reason, and on the terms, an issue is. */
    record ConnectorTokenRotateRefused(UUID actorId, UUID connectorId, Set<Permission> requested)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.token(stamp, AuditRows.Change.refusal(
                    AuditOperation.CONNECTOR_TOKEN_ROTATE, actorId, connectorId,
                    AuditAdministrativeRefusal.PERMISSION_ESCALATION), requested);
        }
    }

    /**
     * A connector token revoked.
     *
     * @param actorId the administrator who revoked it, or {@code null} when the revocation was
     *                part of deleting the connector — that event carries the actor, and repeating
     *                it here would suggest two separate administrative acts
     */
    record ConnectorTokenRevoked(UUID actorId, UUID connectorId) implements Accepted {

        /** Revocation touches one column. */
        public static final List<String> PATHS = List.of("revokedAt");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.connector(
                    stamp, AuditOperation.CONNECTOR_TOKEN_REVOKE, actorId, connectorId, PATHS);
        }
    }

    // ---- SCIM Users ----------------------------------------------------------------------------

    /**
     * A connector creating a SCIM User. The append joins the create's transaction, so a User this
     * service cannot account for is not provisioned.
     *
     * @param connectorId the acting connector
     * @param userId      the created resource's stable id
     */
    record ScimUserCreated(UUID connectorId, UUID userId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.success(AuditOperation.SCIM_USER_CREATE,
                            connectorId, userId, NO_PATHS));
        }
    }

    /**
     * A User create refused — a {@code userName} a live User already holds ({@code UNIQUENESS}),
     * or a password the policy does not accept ({@code INVALID_VALUE}).
     *
     * <p>No subject id, because there is none: the resource was not created, and the existing User
     * that holds the name is not what the event is about. Fail-open in a transaction of its own,
     * which is also what lets it be recorded at all: a create refused as a duplicate has a
     * transaction already doomed by the constraint violation.
     *
     * @param reason why, as the {@code scimType} the connector received
     */
    record ScimUserCreateRejected(UUID connectorId, AuditScimRefusal reason) implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.refusal(AuditOperation.SCIM_USER_CREATE,
                            connectorId, null, reason));
        }
    }

    /**
     * A connector querying the User collection — a bulk read — through {@code GET /Users} or
     * {@code POST /Users/.search}. <strong>Fail-closed</strong>, the one place a READ is treated
     * the way a write is: a bulk read is the shape a credential exfiltrating the directory takes,
     * and an unrecorded one is invisible.
     *
     * <p>Exactly one event per query that ran, whatever it asked for and whatever came back. A
     * query refused before it runs read nothing and is not recorded. The count and the shape are
     * numeric and closed-set; the filter itself never reaches an event.
     *
     * @param resultCount how many resources the response carried
     * @param filter      the filter's shape, or {@code null} when the query had no filter
     */
    record ScimUsersQueried(UUID connectorId, int resultCount, AuditFilterShape filter)
            implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.query(stamp, AuditOperation.SCIM_USER_LIST, connectorId,
                    AuditEvent.USER_RESOURCE_TYPE, resultCount, filter);
        }
    }

    /**
     * A connector replacing or patching a User. One operation for both verbs: which attributes
     * moved is carried as the changed paths.
     *
     * @param changed which attributes moved; empty for a write that changed nothing
     */
    record ScimUserReplaced(UUID connectorId, UUID userId, Set<AuditUserAttribute> changed)
            implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp, AuditRows.Change.success(AuditOperation.SCIM_USER_REPLACE,
                    connectorId, userId, AuditAttribute.paths(changed)));
        }
    }

    /**
     * A User write refused after the User was found — a reused password, a PATCH that would remove
     * a required attribute or found no target, a taken {@code userName}, or an attempt to write the
     * Bootstrap Admin. The write's own transaction is rolling back, so an append that joined it
     * would vanish with it.
     */
    record ScimUserWriteRejected(UUID connectorId, UUID userId, AuditScimRefusal reason)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.refusal(AuditOperation.SCIM_USER_REPLACE,
                            connectorId, userId, reason));
        }
    }

    /**
     * A connector deleting a User. A deletion the trail cannot record rolls back — and with it the
     * tombstone and the after-commit revocation, which never fires. No path: the whole resource
     * went, which the operation says on its own.
     */
    record ScimUserDeleted(UUID connectorId, UUID userId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.success(AuditOperation.SCIM_USER_DELETE,
                            connectorId, userId, NO_PATHS));
        }
    }

    /** A User deletion refused after the User was found — an attempt to delete the Bootstrap Admin. */
    record ScimUserDeleteRejected(UUID connectorId, UUID userId, AuditScimRefusal reason)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.refusal(AuditOperation.SCIM_USER_DELETE,
                            connectorId, userId, reason));
        }
    }

    // ---- SCIM Groups and searches --------------------------------------------------------------

    /**
     * A connector creating a SCIM Group. A Group confers authority, so an unrecorded Group creation
     * is an unrecorded grant of access.
     *
     * @param groupId the created Group's stable id
     */
    record ScimGroupCreated(UUID connectorId, UUID groupId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.group(stamp,
                    AuditRows.Change.success(AuditOperation.SCIM_GROUP_CREATE,
                            connectorId, groupId, NO_PATHS));
        }
    }

    /**
     * A Group create refused. No subject id: the Group was not created, and the existing resource
     * that caused the refusal — a Group holding the name, a member id naming no live User — is not
     * what the event is about.
     */
    record ScimGroupCreateRejected(UUID connectorId, AuditScimRefusal reason) implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.group(stamp,
                    AuditRows.Change.refusal(AuditOperation.SCIM_GROUP_CREATE,
                            connectorId, null, reason));
        }
    }

    /**
     * A connector changing a Group's name or membership. One operation for PUT and PATCH, because
     * the stored change is the same; which attributes moved is carried as the changed paths.
     *
     * @param changed which attributes moved — a closed set, because the recorded path list is a
     *                field readers filter on and a caller-assembled one is a place a submitted
     *                value could be written
     */
    record ScimGroupReplaced(UUID connectorId, UUID groupId, Set<AuditGroupAttribute> changed)
            implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.group(stamp, AuditRows.Change.success(
                    AuditOperation.SCIM_GROUP_REPLACE,
                    connectorId, groupId, AuditAttribute.paths(changed)));
        }
    }

    /**
     * A Group write refused — including an attempt to rename the Admin group or to remove the
     * Bootstrap Admin's membership of it, which carry {@link AuditScimRefusal#MUTABILITY}. This one
     * DOES name the Group: the resource exists, the write was aimed at it, and an attempt to
     * provision the recovery authority away is exactly what an administrator searches for by that
     * Group's id.
     */
    record ScimGroupWriteRejected(UUID connectorId, UUID groupId, AuditScimRefusal reason)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.group(stamp,
                    AuditRows.Change.refusal(AuditOperation.SCIM_GROUP_REPLACE,
                            connectorId, groupId, reason));
        }
    }

    /** A connector deleting a Group: a revoked grant of authority is not forgotten. */
    record ScimGroupDeleted(UUID connectorId, UUID groupId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.group(stamp, AuditRows.Change.success(AuditOperation.SCIM_GROUP_DELETE,
                    connectorId, groupId, AuditRows.MEMBERSHIP_PATHS));
        }
    }

    /**
     * A connector querying the Group collection — {@code GET /Groups} or
     * {@code POST /Groups/.search} — for the reasons and on the terms {@link ScimUsersQueried} is.
     */
    record ScimGroupsQueried(UUID connectorId, int resultCount, AuditFilterShape filter)
            implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.query(stamp, AuditOperation.SCIM_GROUP_LIST, connectorId,
                    AuditEvent.GROUP_RESOURCE_TYPE, resultCount, filter);
        }
    }

    /**
     * A connector searching Users and Groups together — {@code POST /.search} — as one bulk read.
     * One event rather than one per type, because it was one request and one result set.
     */
    record ScimResourcesQueried(UUID connectorId, int resultCount, AuditFilterShape filter)
            implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.query(stamp, AuditOperation.SCIM_RESOURCE_LIST, connectorId,
                    AuditEvent.USER_AND_GROUP_RESOURCE_TYPE, resultCount, filter);
        }
    }

    // ---- Seeding ---------------------------------------------------------------------------------

    /**
     * The server creating a resource it reserves for recovery, on a database that did not have it.
     * Fail-closed: seeding runs at startup, where a failure is a failure to boot.
     *
     * <p>No actor: seeding is the deployment establishing its own recovery path, not a principal
     * acting. The seeded resource is the subject, so the event reads as "this identity came into
     * existence".
     *
     * @param resourceId the seeded resource's stable id
     * @param resource   which reserved resource was seeded, deciding the recorded resource type
     */
    record ReservedResourceSeeded(UUID resourceId, AuditSeededResource resource)
            implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.resource(stamp,
                    AuditRows.Change.success(
                            AuditOperation.SCIM_RESOURCE_SEED, null, resourceId, NO_PATHS),
                    resource.resourceType());
        }
    }

    /**
     * Seeding put the Bootstrap Admin back into the Admin group, because something outside SCIM had
     * removed it — a different fact from {@link ReservedResourceSeeded}, so a restore never reads
     * as a first-boot seed. Recorded against the GROUP whose membership moved, with no actor and
     * the {@code members} path a connector's membership change records.
     *
     * <p>The restored User is not written into the event: only the Bootstrap Admin's membership is
     * ever restored, and the reservation says which identity that is.
     *
     * @param groupId the Admin group's id
     * @param userId  the Bootstrap Admin whose membership was restored
     */
    record ReservedMembershipRestored(UUID groupId, UUID userId) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.group(stamp, AuditRows.Change.success(
                    AuditOperation.SCIM_RESOURCE_SEED,
                    null, groupId, AuditRows.MEMBERSHIP_PATHS));
        }
    }

    // ---- Sessions, dormancy and Roles -----------------------------------------------------------

    /**
     * The outcome of a Session revocation — ending a User's sessions after a committed change,
     * whatever triggered it. {@link Committed}: the change it follows is already durable, so
     * failing here could undo nothing (ADR 0004, 2026-10-10 addendum). A failed revocation is
     * recorded with a server-error status class, because it was this service, not the caller,
     * that could not finish the job.
     *
     * @param actorId   who caused it: the connector whose write it followed, the administrator who
     *                  forced a password change — or {@code null} when the dormancy job, a Login or
     *                  the User's own self-service change did
     * @param paths     the attributes whose change ended the sessions; empty when no attribute
     *                  changed (a deletion, a lockout, a Login)
     * @param causes    why the sessions ended, recorded as the event's reason; never empty
     * @param succeeded whether the session store ended them
     */
    record UserSessionsRevoked(
            UUID actorId,
            UUID userId,
            Set<AuditUserAttribute> paths,
            Set<AuditSessionRevocationCause> causes,
            boolean succeeded) implements Committed {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp, new AuditRows.Change(AuditOperation.USER_SESSIONS_REVOKE,
                    succeeded ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    actorId, userId, AuditAttribute.paths(paths),
                    succeeded ? AuditEvent.STATUS_OK : AuditEvent.STATUS_SERVER_ERROR,
                    AuditRows.causeNames(causes)));
        }
    }

    /**
     * The dormancy job locking a dormant User. The append joins the job's transaction, so a lock
     * the trail cannot record is not imposed. No actor, because the scheduled job is not a
     * principal; the operation is what names it.
     */
    record DormancyLockout(UUID userId) implements Accepted {

        /** What a dormancy lockout sets: the lock and its cause; the failure run does not move. */
        public static final List<String> PATHS = List.of("lockedAt", "lockCause");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.success(AuditOperation.DORMANCY_LOCKOUT, null, userId, PATHS));
        }
    }

    /**
     * The dormancy job removing a dormant User's direct membership of every mapped Group it held,
     * as one event naming the User and the Roles it lost — sorted, distinct and comma-joined.
     * Fail-closed and actorless, for the reasons {@link DormancyLockout} is.
     *
     * @param roles the Roles the removed memberships conferred; never empty
     * @throws IllegalArgumentException when {@code roles} is empty
     */
    record DormancyRoleRevocation(UUID userId, List<Role> roles) implements Accepted {

        public DormancyRoleRevocation {
            if (roles.isEmpty()) {
                throw new IllegalArgumentException("a role revocation names the Roles lost");
            }
            roles = List.copyOf(roles);
        }

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.dormancyRoleRevocation(stamp, userId, roles);
        }
    }

    /**
     * A User gaining a mapped Group's Role because a connector's write added it to the Group: a
     * change of power this service cannot account for does not happen.
     *
     * @param connectorId the connector whose write added the User
     * @param userId      the User that gained the Role, recorded as the subject
     * @param groupId     the mapped Group, recorded as the resource
     * @param role        the Role, recorded by its name in the role mapping
     */
    record RoleGranted(UUID connectorId, UUID userId, UUID groupId, Role role) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.role(stamp, AuditOperation.ROLE_GRANT, connectorId, userId, groupId,
                    role);
        }
    }

    /**
     * A User losing a mapped Group's Role because a connector's write removed it from the Group —
     * a PATCH, a PUT, or the Group's deletion — recorded for the reason {@link RoleGranted} is.
     */
    record RoleRevoked(UUID connectorId, UUID userId, UUID groupId, Role role) implements Accepted {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.role(stamp, AuditOperation.ROLE_REVOKE, connectorId, userId, groupId,
                    role);
        }
    }

    // ---- Passwords and authorization ------------------------------------------------------------

    /**
     * An administrator requiring a password change of a User — directly, or by lifting its
     * lockout. A requirement this service cannot account for is not imposed.
     */
    record PasswordChangeRequired(UUID actorId, UUID subjectId) implements Accepted {

        /** What requiring a password change sets: the flag, and nothing about the credential. */
        public static final List<String> PATHS = List.of("passwordChangeRequiredSince");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp, AuditRows.Change.success(
                    AuditOperation.PASSWORD_CHANGE_REQUIRE, actorId, subjectId, PATHS));
        }
    }

    /** A forced password change refused. */
    record PasswordChangeRequirementRefused(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason) implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp, AuditRows.Change.refusal(
                    AuditOperation.PASSWORD_CHANGE_REQUIRE, actorId, subjectId, reason));
        }
    }

    /**
     * A User replacing its own password: a credential change this service cannot account for does
     * not happen. The User is its own actor, and the paths name the credential and the flag it
     * clears — never a value.
     */
    record PasswordChanged(UUID userId) implements Accepted {

        /** What a self-service change moves: the credential, and the flag it clears. */
        public static final List<String> PATHS = List.of("password", "passwordChangeRequiredSince");

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.success(AuditOperation.PASSWORD_CHANGE,
                            userId, userId, PATHS));
        }
    }

    /** A self-service password change refused; the caller is already receiving the refusal. */
    record PasswordChangeRefused(UUID userId, AuditPasswordChangeRefusal reason)
            implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.user(stamp,
                    AuditRows.Change.refusal(AuditOperation.PASSWORD_CHANGE,
                            userId, userId, reason));
        }
    }

    /**
     * An authorization refusal on the application chain; the caller is already receiving
     * {@code 403}. Recorded with one generic reason, never the Permission, Role or rule.
     *
     * @param userId    the refused User's stable id, or {@code null} when the session names none
     * @param operation the refused request as the trail identifies one: its method, the route
     *                  TEMPLATE it addressed (never the resolved path) and its correlation id.
     *                  Carried rather than read from the request context, because a chain-level
     *                  refusal happens before the dispatcher has matched a route that context could
     *                  report
     */
    record AccessDenied(UUID userId, AuditRequest operation) implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.accessDenied(stamp, userId, AuditEvent.USER_RESOURCE_TYPE, operation);
        }
    }

    /**
     * An authorization refusal on the SCIM chain: a valid connector token lacking the Permission
     * the request needs, recorded on the terms and with the one generic reason
     * {@link AccessDenied} uses. The connector is the actor and the subject.
     *
     * @param connectorId the refused token's connector
     * @param operation   the refused request: method, route template, correlation id
     */
    record ConnectorAccessDenied(UUID connectorId, AuditRequest operation) implements Refused {

        @Override
        public AuditEvent toEvent(Stamp stamp) {
            return AuditRows.accessDenied(
                    stamp, connectorId, AuditEvent.CONNECTOR_RESOURCE_TYPE, operation);
        }
    }
}
