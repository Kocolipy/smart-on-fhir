package com.example.backend.audit.application;

import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventRepository;
import com.example.backend.audit.domain.AuditFilterShape;
import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditLockCause;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditRequest;
import com.example.backend.audit.domain.AuditRequestContext;
import com.example.backend.audit.domain.AuditScimRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.audit.domain.OperationalAlerts;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.Role;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way anything in this service records that something happened.
 *
 * <p>Every method below takes ids and members of closed sets, and <em>no public
 * method takes a {@code String}</em>. That is not a style preference: a username,
 * a password and a bearer value are all strings, so a boundary that admits no
 * string cannot be handed one by a caller who did not read the rule. The event's
 * own textual fields — the resource type, the status class, the error code, the
 * route template — are filled in here and by the request-context adapter, from
 * vocabularies this slice owns. {@code ArchitectureTest} holds the boundary to
 * that shape, and {@code semgrep/rules/service-security.yml} flags a call that
 * tries to pass a credential-named value through it.
 *
 * <h2>Two failure semantics, deliberately</h2>
 *
 * <p>An append is either <strong>fail-closed</strong> or <strong>fail-open with an
 * alert</strong>, and which one it is follows from what the triggering request was
 * going to return:
 *
 * <ul>
 *   <li><strong>Fail-closed</strong> for an operation that would otherwise
 *       succeed — an administrator's disable, enable or unlock, an accepted login,
 *       a logout. The append joins the caller's transaction and flushes, so an
 *       event that cannot be written rolls the mutation it was recording back. A
 *       change this service cannot account for does not happen.
 *   <li><strong>Fail-open with an alert</strong> for an event on a path that is
 *       already refusing the request — a rejected login and the lockout it may
 *       impose. Here the
 *       original outcome is a bare {@code 401}, and it must stay one: turning a
 *       refused login into a {@code 500} because the trail is unavailable would
 *       tell an attacker something about the state of the service, and would
 *       change the answer to a question that was already answered. The append
 *       runs in its own transaction so its rollback cannot take the caller's with
 *       it, and its failure raises an operational alert instead of propagating.
 * </ul>
 *
 * <p>See {@code /docs/adr/0004-audit-append-failure-semantics.md}.
 */
@Service
public class AuditTrailService implements AuditTrail {

    /** The lockout columns, as the paths an event reports as changed. */
    private static final List<String> LOCKOUT_PATHS =
            List.of("failedLoginAttempts", "lockedAt", "lockCause");

    /** What a dormancy lockout sets: the lock and its cause; the failure run does not move. */
    private static final List<String> DORMANCY_LOCK_PATHS = List.of("lockedAt", "lockCause");

    /** What requiring a password change sets: the flag, and nothing about the credential. */
    private static final List<String> PASSWORD_CHANGE_REQUIRED_PATHS =
            List.of("passwordChangeRequiredSince");

    /** What a self-service change moves: the credential, and the flag it clears. */
    private static final List<String> PASSWORD_CHANGED_PATHS =
            List.of("password", "passwordChangeRequiredSince");

    /** What a Group rename changes. */
    private static final List<String> GROUP_NAME_PATHS = List.of("displayName");

    /** What a membership change changes, and the only path a PATCH of members touches. */
    private static final List<String> GROUP_MEMBER_PATHS = List.of("members");

    /** What a change to the calling connector's own alias changes; never the alias value. */
    private static final List<String> EXTERNAL_ID_PATHS = List.of("externalId");

    /** A rejected login lengthens the failure run and nothing else. */
    private static final List<String> FAILURE_RUN_PATHS = List.of("failedLoginAttempts");

    /** What deleting a connector changes on the connector row itself. */
    private static final List<String> CONNECTOR_DELETED_PATHS = List.of("deletedAt");

    /**
     * A token's issue is the appearance of a whole row, so the path list names the
     * two fields that decide what the credential can do and for how long — the
     * questions an administrator reading the trail is actually asking. It never
     * names the digest, and there is no path for a value that was not stored.
     */
    private static final List<String> TOKEN_ISSUE_PATHS = List.of("permissions", "expiresAt");

    /** Rotation shortens the old token and records which token replaced it. */
    private static final List<String> TOKEN_ROTATE_PATHS =
            List.of("expiresAt", "replacedByTokenId");

    /** Revocation touches one column. */
    private static final List<String> TOKEN_REVOKE_PATHS = List.of("revokedAt");

    /**
     * The path list of an event that changed nothing — a read, a refusal, a creation recorded as
     * the appearance of a whole resource. One constant rather than an empty list built at each
     * call site, so "nothing changed" is spelled once.
     */
    private static final List<String> NO_PATHS = List.of();

    /**
     * The one reason an authorization refusal is recorded with, whichever rule refused: generic,
     * so the trail never names a Permission, a Role or the policy.
     */
    static final String INSUFFICIENT_PERMISSIONS = "INSUFFICIENT_PERMISSIONS";

    private final AuditEventRepository events;
    private final AuditRequestContext requests;
    private final OperationalAlerts alerts;
    private final Clock clock;

    /**
     * Runs a fail-open append in a transaction of its own.
     *
     * <p>A {@link TransactionTemplate} rather than a second {@code @Transactional}
     * method on this class, because a self-invocation does not pass through the
     * proxy: the annotation would be silently ignored and the append would run in
     * the caller's transaction, where its rollback would take the caller's write
     * with it — the exact opposite of what fail-open means.
     */
    private final TransactionTemplate isolatedAppend;

    public AuditTrailService(
            AuditEventRepository events,
            AuditRequestContext requests,
            OperationalAlerts alerts,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.requests = requests;
        this.alerts = alerts;
        this.clock = clock;
        this.isolatedAppend = new TransactionTemplate(transactionManager);
        this.isolatedAppend.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records an accepted login. Fail-closed: a session this service cannot
     * account for is not issued.
     */
    @Transactional
    @Override
    public void recordLoginSuccess(UUID accountId, AuditLoginMethod method) {
        append(loginEvent(
                AuditOperation.LOGIN_SUCCESS,
                AuditOutcome.SUCCESS,
                accountId,
                accountId,
                NO_PATHS,
                AuditEvent.STATUS_OK,
                null,
                method));
    }

    /**
     * Records a refused login. Fail-open: the caller is already receiving a bare
     * {@code 401} and that answer does not change.
     *
     * @param subjectId stable id of the account the attempt named, or {@code null}
     *                  when the submitted username names no account — the one
     *                  thing that must not be recorded in its place
     */
    @Override
    public void recordLoginFailure(
            UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method) {
        appendRaisingAlertOnFailure(loginEvent(
                AuditOperation.LOGIN_FAILURE,
                AuditOutcome.FAILURE,
                null,
                subjectId,
                FAILURE_RUN_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name(),
                method));
    }

    /**
     * Records a session ended by its holder. Fail-closed, and recorded before the
     * session is invalidated, so a logout this service cannot account for does not
     * happen.
     */
    @Transactional
    @Override
    public void recordLogout(UUID accountId) {
        append(event(
                AuditOperation.LOGOUT,
                AuditOutcome.SUCCESS,
                accountId,
                accountId,
                NO_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a failure run reaching the limit. Fail-open for the same reason as
     * {@link #recordLoginFailure}: it happens on the rejected-login path, whose
     * answer is already a refusal.
     */
    @Override
    public void recordLockoutSet(UUID accountId) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.LOCKOUT_SET,
                AuditOutcome.SUCCESS,
                null,
                accountId,
                LOCKOUT_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                null));
    }

    /**
     * Records a lockout an administrator lifted. Fail-closed: the unlock is a
     * change an administrator asked for and it does not happen unrecorded.
     *
     * <p>No expiry counterpart exists. A lock has no duration, so the only lift is
     * this one and every {@code LOCKOUT_LIFT} event therefore carries the
     * administrator who performed it; the event needs no detail saying which kind
     * of lift it was, because there is only one kind. It does carry the cause of the lock it
     * lifted, as {@code errorCode} — the column every event's closed-set reason lives in — so a
     * reviewer can tell a dormant account brought back from a forgotten password reset; an Unlock
     * of a User that was not locked carries none.
     */
    @Transactional
    @Override
    public void recordLockoutLiftedByUnlock(
            UUID actorId, UUID subjectId, AuditLockCause lockCause) {
        append(event(
                AuditOperation.LOCKOUT_LIFT,
                AuditOutcome.SUCCESS,
                actorId,
                subjectId,
                LOCKOUT_PATHS,
                AuditEvent.STATUS_OK,
                lockCause == null ? null : lockCause.name()));
    }

    /**
     * Records a connector created. Fail-closed: the connector exists only if the
     * event does.
     *
     * <p>Every connector and token event below is fail-closed, for the reason the
     * administrative account writes are: an administrator asked for the change and
     * the change is not worth having unaccounted for. A credential this service
     * cannot say who minted is worse than a failed mint an administrator retries.
     */
    @Transactional
    @Override
    public void recordConnectorCreated(UUID actorId, UUID connectorId) {
        append(connectorEvent(
                AuditOperation.CONNECTOR_CREATE, actorId, connectorId, NO_PATHS));
    }

    /** Records a connector deleted, with its tokens and aliases. Fail-closed. */
    @Transactional
    @Override
    public void recordConnectorDeleted(UUID actorId, UUID connectorId) {
        append(connectorEvent(
                AuditOperation.CONNECTOR_DELETE, actorId, connectorId, CONNECTOR_DELETED_PATHS));
    }

    /** Records a token minted for a connector. Fail-closed. */
    @Transactional
    @Override
    public void recordConnectorTokenIssued(
            UUID actorId, UUID connectorId, Set<Permission> granted) {
        append(tokenEvent(AuditOperation.CONNECTOR_TOKEN_ISSUE, true, actorId, connectorId,
                TOKEN_ISSUE_PATHS, granted));
    }

    /** Records a connector's token replaced. Fail-closed. */
    @Transactional
    @Override
    public void recordConnectorTokenRotated(
            UUID actorId, UUID connectorId, Set<Permission> granted) {
        append(tokenEvent(AuditOperation.CONNECTOR_TOKEN_ROTATE, true, actorId, connectorId,
                TOKEN_ROTATE_PATHS, granted));
    }

    /** Records a token issue refused as an escalation. Fail-open with an alert. */
    @Override
    public void recordConnectorTokenIssueRefused(
            UUID actorId, UUID connectorId, Set<Permission> requested) {
        appendRaisingAlertOnFailure(tokenEvent(AuditOperation.CONNECTOR_TOKEN_ISSUE, false,
                actorId, connectorId, TOKEN_ISSUE_PATHS, requested));
    }

    /** Records a rotation refused as an escalation. Fail-open with an alert. */
    @Override
    public void recordConnectorTokenRotateRefused(
            UUID actorId, UUID connectorId, Set<Permission> requested) {
        appendRaisingAlertOnFailure(tokenEvent(AuditOperation.CONNECTOR_TOKEN_ROTATE, false,
                actorId, connectorId, TOKEN_ROTATE_PATHS, requested));
    }

    /**
     * Records a connector token revoked. Fail-closed.
     *
     * @param actorId {@code null} when the revocation came from deleting the
     *                connector rather than from a revoke request of its own
     */
    @Transactional
    @Override
    public void recordConnectorTokenRevoked(UUID actorId, UUID connectorId) {
        append(connectorEvent(
                AuditOperation.CONNECTOR_TOKEN_REVOKE, actorId, connectorId, TOKEN_REVOKE_PATHS));
    }

    private void append(AuditEvent event) {
        events.append(event);
    }

    /**
     * Records a connector creating a User. Fail-closed: the append joins the create's
     * transaction, so a User this service cannot account for is not provisioned.
     */
    @Transactional
    @Override
    public void recordScimUserCreated(UUID connectorId, UUID userId) {
        append(userEvent(
                AuditOutcome.SUCCESS,
                connectorId,
                userId,
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a User create refused. Fail-open, for the reason a rejected login is: the
     * caller is already receiving a refusal, and a create refused as a duplicate has a
     * transaction already doomed by the constraint violation, so an append that joined it
     * would be rolled back with it. The isolated transaction is what lets the refusal be
     * recorded at all.
     */
    @Override
    public void recordScimUserCreateRejected(UUID connectorId, AuditScimRefusal reason) {
        appendRaisingAlertOnFailure(userEvent(
                AuditOutcome.FAILURE,
                connectorId,
                null,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /**
     * Records a connector querying the User collection. <strong>Fail-closed</strong>,
     * which is the one place a READ is treated the way a write is.
     *
     * <p>The reason is what the event is for. A bulk read is the shape a credential
     * exfiltrating the directory takes, and an unrecorded one is invisible: if the
     * append cannot commit, the honest outcome is that the caller gets an error rather
     * than that the service hands over every User and forgets it did. That is a
     * deliberate trade of availability for accountability on this endpoint, and it is
     * the opposite trade from the rejected-login path, where the request was being
     * refused anyway and had nothing to hand over.
     */
    @Transactional
    @Override
    public void recordScimUsersQueried(UUID connectorId, int resultCount, AuditFilterShape filter) {
        append(queryEvent(
                AuditOperation.SCIM_USER_LIST,
                connectorId,
                AuditEvent.USER_RESOURCE_TYPE,
                resultCount,
                filter));
    }

    /**
     * Records a connector creating a Group. Fail-closed: the append joins the create's
     * transaction, so a Group this service cannot account for is not provisioned.
     *
     * <p>That matters more for a Group than for a User. A Group confers authority, so an
     * unrecorded Group creation is an unrecorded grant of access.
     */
    @Transactional
    @Override
    public void recordScimGroupCreated(UUID connectorId, UUID groupId) {
        append(groupEvent(
                AuditOperation.SCIM_GROUP_CREATE,
                AuditOutcome.SUCCESS,
                connectorId,
                groupId,
                NO_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a Group create refused. Fail-open, for the reason a refused User create is:
     * the caller is already receiving a refusal, and a create refused by a constraint
     * violation has a doomed transaction that would roll a joined append back with it.
     */
    @Override
    public void recordScimGroupCreateRejected(UUID connectorId, AuditScimRefusal reason) {
        appendRaisingAlertOnFailure(groupEvent(
                AuditOperation.SCIM_GROUP_CREATE,
                AuditOutcome.FAILURE,
                connectorId,
                null,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /** Records a Group's name or membership changed. Fail-closed. */
    @Transactional
    @Override
    public void recordScimGroupReplaced(
            UUID connectorId, UUID groupId, Set<AuditGroupAttribute> changed) {
        append(groupEvent(
                AuditOperation.SCIM_GROUP_REPLACE,
                AuditOutcome.SUCCESS,
                connectorId,
                groupId,
                changedPaths(changed),
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a Group write refused. Fail-open with an alert.
     *
     * <p>This is the event that records an attempt to rename the Admin group or to remove the
     * Bootstrap Admin's membership of it, and it is fail-open for the same reason the other
     * refusals are: the caller's {@code 400} or {@code 409} must not turn into a
     * {@code 500}, and nothing was written for a rollback to undo. The alert is what stops a
     * trail outage from making these silent.
     */
    @Override
    public void recordScimGroupWriteRejected(
            UUID connectorId, UUID groupId, AuditScimRefusal reason) {
        appendRaisingAlertOnFailure(groupEvent(
                AuditOperation.SCIM_GROUP_REPLACE,
                AuditOutcome.FAILURE,
                connectorId,
                groupId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /** Records a Group deleted. Fail-closed: a revoked grant of authority is not forgotten. */
    @Transactional
    @Override
    public void recordScimGroupDeleted(UUID connectorId, UUID groupId) {
        append(groupEvent(
                AuditOperation.SCIM_GROUP_DELETE,
                AuditOutcome.SUCCESS,
                connectorId,
                groupId,
                GROUP_MEMBER_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a connector querying the Group collection. <strong>Fail-closed</strong>, for the
     * reason the User collection read is: a bulk read is the shape a credential enumerating
     * the directory takes, and the honest outcome of an append that cannot commit is that the
     * caller gets an error rather than that the service hands over every Group and forgets.
     */
    @Transactional
    @Override
    public void recordScimGroupsQueried(UUID connectorId, int resultCount, AuditFilterShape filter) {
        append(queryEvent(
                AuditOperation.SCIM_GROUP_LIST,
                connectorId,
                AuditEvent.GROUP_RESOURCE_TYPE,
                resultCount,
                filter));
    }

    /** Records a base search across Users and Groups. Fail-closed, as the other bulk reads are. */
    @Transactional
    @Override
    public void recordScimResourcesQueried(
            UUID connectorId, int resultCount, AuditFilterShape filter) {
        append(queryEvent(
                AuditOperation.SCIM_RESOURCE_LIST,
                connectorId,
                AuditEvent.USER_AND_GROUP_RESOURCE_TYPE,
                resultCount,
                filter));
    }

    /**
     * Records the server seeding a reserved resource. Fail-closed: a recovery identity this
     * service cannot account for is one it does not create, and seeding runs at startup where
     * a failure is a failure to boot rather than a request nobody can retry.
     *
     * <p>The actor is {@code null} because nobody acted. The subject is the seeded resource
     * itself, so the event reads as "this identity came into existence", which is what an
     * administrator investigating an unexpected administrator needs it to say.
     */
    @Transactional
    @Override
    public void recordReservedResourceSeeded(UUID resourceId, boolean group) {
        append(event(
                AuditOperation.SCIM_RESOURCE_SEED,
                AuditOutcome.SUCCESS,
                null,
                resourceId,
                group ? AuditEvent.GROUP_RESOURCE_TYPE : AuditEvent.USER_RESOURCE_TYPE,
                NO_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * The restore, recorded against the GROUP whose membership moved, with the restored User
     * named in the changed path.
     *
     * <p>Subject is the Group rather than the User because that is the resource whose
     * representation changed, which is how every other Group write in this trail is recorded —
     * an operator reading the Admin group's history sees the removal's consequence in the same
     * place as the removal would have been.
     *
     * <p>Fail-closed like the seed above: startup is where a failure is a failure to boot, and a
     * deployment that restored its own administrative authority without being able to say so is
     * not a deployment that should come up.
     *
     * <p>The restored User is NOT written into the event's error-code field, which is what it
     * would take to name it here — that field carries a closed-set failure reason and a successful
     * restore has none. Which User it was needs no recording anyway: only the Bootstrap Admin's
     * membership is ever restored, and the reservation says which identity that is.
     */
    @Transactional
    @Override
    public void recordReservedMembershipRestored(UUID groupId, UUID userId) {
        append(event(
                AuditOperation.SCIM_RESOURCE_SEED,
                AuditOutcome.SUCCESS,
                null,
                groupId,
                AuditEvent.GROUP_RESOURCE_TYPE,
                changedPaths(Set.of(AuditGroupAttribute.MEMBERS)),
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a User replaced or patched. Fail-closed: the append joins the write's transaction,
     * so a change to an identity this service cannot account for rolls back with it.
     */
    @Transactional
    @Override
    public void recordScimUserReplaced(
            UUID connectorId, UUID userId, Set<AuditUserAttribute> changed) {
        append(event(
                AuditOperation.SCIM_USER_REPLACE,
                AuditOutcome.SUCCESS,
                connectorId,
                userId,
                userPaths(changed),
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records a User write refused. Fail-open with an alert, for the reason a refused Group write
     * is: the caller's refusal must not become a {@code 500}, and the write's own transaction is
     * rolling back, so an append that joined it would vanish with it.
     */
    @Override
    public void recordScimUserWriteRejected(
            UUID connectorId, UUID userId, AuditScimRefusal reason) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.SCIM_USER_REPLACE,
                AuditOutcome.FAILURE,
                connectorId,
                userId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /**
     * Records a User deleted. Fail-closed: the append joins the deletion's transaction, so a
     * deletion the trail cannot record rolls back — and with it the tombstone and the
     * after-commit revocation, which never fires.
     */
    @Transactional
    @Override
    public void recordScimUserDeleted(UUID connectorId, UUID userId) {
        append(event(
                AuditOperation.SCIM_USER_DELETE,
                AuditOutcome.SUCCESS,
                connectorId,
                userId,
                NO_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /** Records a User deletion refused. Fail-open with an alert, as a refused write is. */
    @Override
    public void recordScimUserDeleteRejected(
            UUID connectorId, UUID userId, AuditScimRefusal reason) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.SCIM_USER_DELETE,
                AuditOutcome.FAILURE,
                connectorId,
                userId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /**
     * Records a post-commit session revocation and whether it worked. Fail-open with an alert:
     * the write it follows is already durable, so failing here could undo nothing and would only
     * turn a committed write into an error.
     *
     * <p>A failed revocation is recorded with a server-error status class, because the write
     * succeeded and it was this service, not the caller, that could not finish the job.
     */
    @Override
    public void recordUserSessionsRevoked(
            UUID connectorId, UUID userId, Set<AuditUserAttribute> causes, boolean succeeded) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.USER_SESSIONS_REVOKE,
                succeeded ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                connectorId,
                userId,
                userPaths(causes),
                succeeded ? AuditEvent.STATUS_OK : AuditEvent.STATUS_SERVER_ERROR,
                null));
    }

    /** The recorded path names for a set of changed User attributes, in a stable order. */
    private static List<String> userPaths(Set<AuditUserAttribute> changed) {
        return changed.stream()
                .sorted()
                .map(attribute -> switch (attribute) {
                    case USER_NAME -> "userName";
                    case NAME -> "name";
                    case DISPLAY_NAME -> "displayName";
                    case PREFERRED_LANGUAGE -> "preferredLanguage";
                    case LOCALE -> "locale";
                    case TIMEZONE -> "timezone";
                    case ACTIVE -> "active";
                    case PASSWORD -> "password";
                    case EMAILS -> "emails";
                    case EXTERNAL_ID -> "externalId";
                    case GROUPS -> "groups";
                })
                .toList();
    }

    /**
     * Records the dormancy job locking a dormant User. Fail-closed: the append joins the job's
     * transaction, so a lock this service cannot account for rolls back with it — and the
     * after-commit revocation never fires.
     *
     * <p>The actor is {@code null} because the scheduled job is not a principal; the operation is
     * what names it, as seeding's does. The changed paths are the lock instant and its cause — the
     * failure run does not move.
     */
    @Transactional
    @Override
    public void recordDormancyLockout(UUID userId) {
        append(event(
                AuditOperation.DORMANCY_LOCKOUT,
                AuditOutcome.SUCCESS,
                null,
                userId,
                DORMANCY_LOCK_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /**
     * Records the dormancy job removing a dormant User's mapped Group memberships: one event, the
     * User as subject and resource, {@code groups} as the changed path, and the Roles lost named in
     * {@code role}, sorted and comma-joined as {@code changed_paths} is. Fail-closed and actorless,
     * as {@link #recordDormancyLockout} is: an unrecorded change to who holds which Role is the
     * gap this trail exists to close.
     */
    @Transactional
    @Override
    public void recordDormancyRoleRevocation(UUID userId, List<Role> roles) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("a role revocation names the Roles lost");
        }
        // The one actorless User event that names a Role, so it is built here rather than by
        // copying event()'s result — the request fields are the job's (empty) request all the same.
        AuditRequest request = requests.current();
        append(new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                AuditOperation.DORMANCY_ROLE_REVOCATION,
                AuditOutcome.SUCCESS,
                null,
                userId,
                AuditEvent.USER_RESOURCE_TYPE,
                userId,
                userPaths(Set.of(AuditUserAttribute.GROUPS)),
                AuditEvent.STATUS_OK,
                null,
                request.method(),
                request.pathTemplate(),
                request.requestId(),
                null,
                null,
                roles.stream().map(Role::name).distinct().sorted()
                        .collect(Collectors.joining(","))));
    }

    /** Records a User gaining a mapped Group's Role. Fail-closed. */
    @Transactional
    @Override
    public void recordRoleGranted(UUID connectorId, UUID userId, UUID groupId, Role role) {
        append(roleEvent(AuditOperation.ROLE_GRANT, connectorId, userId, groupId, role));
    }

    /** Records a User losing a mapped Group's Role. Fail-closed. */
    @Transactional
    @Override
    public void recordRoleRevoked(UUID connectorId, UUID userId, UUID groupId, Role role) {
        append(roleEvent(AuditOperation.ROLE_REVOKE, connectorId, userId, groupId, role));
    }

    /**
     * A change of power through a mapped Group's membership: the subject is the User whose power
     * changed, the resource the Group whose membership moved, and the Role is named. The one event
     * whose subject and resource differ, because it is about both — and naming the User here is
     * what the plain Group write event deliberately does not do.
     */
    private AuditEvent roleEvent(
            AuditOperation operation, UUID connectorId, UUID userId, UUID groupId, Role role) {
        AuditRequest request = requests.current();
        return new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                operation,
                AuditOutcome.SUCCESS,
                connectorId,
                userId,
                AuditEvent.GROUP_RESOURCE_TYPE,
                groupId,
                GROUP_MEMBER_PATHS,
                AuditEvent.STATUS_OK,
                null,
                request.method(),
                request.pathTemplate(),
                request.requestId(),
                null,
                null,
                role.name());
    }

    /** Records an administrator requiring a password change. Fail-closed. */
    @Transactional
    @Override
    public void recordPasswordChangeRequired(UUID actorId, UUID subjectId) {
        append(event(
                AuditOperation.PASSWORD_CHANGE_REQUIRE,
                AuditOutcome.SUCCESS,
                actorId,
                subjectId,
                PASSWORD_CHANGE_REQUIRED_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /** Records a forced password change refused. Fail-open with an alert. */
    @Override
    public void recordPasswordChangeRequirementRefused(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.PASSWORD_CHANGE_REQUIRE,
                AuditOutcome.FAILURE,
                actorId,
                subjectId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /** Records an Unlock refused. Fail-open with an alert. */
    @Override
    public void recordUnlockRefused(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.LOCKOUT_LIFT,
                AuditOutcome.FAILURE,
                actorId,
                subjectId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /**
     * Records a User replacing its own password. Fail-closed. The User is its own actor, and the
     * paths name the credential and the flag — never a value.
     */
    @Transactional
    @Override
    public void recordPasswordChanged(UUID userId) {
        append(event(
                AuditOperation.PASSWORD_CHANGE,
                AuditOutcome.SUCCESS,
                userId,
                userId,
                PASSWORD_CHANGED_PATHS,
                AuditEvent.STATUS_OK,
                null));
    }

    /** Records a self-service password change refused. Fail-open with an alert. */
    @Override
    public void recordPasswordChangeRefused(UUID userId, AuditPasswordChangeRefusal reason) {
        appendRaisingAlertOnFailure(event(
                AuditOperation.PASSWORD_CHANGE,
                AuditOutcome.FAILURE,
                userId,
                userId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                reason.name()));
    }

    /**
     * A Group lifecycle event: the actor is a connector and the resource type is
     * {@code Group}, whose id is also the subject — or {@code null} when the write produced
     * no Group to name.
     */
    private AuditEvent groupEvent(
            AuditOperation operation,
            AuditOutcome outcome,
            UUID connectorId,
            UUID groupId,
            List<String> changedPaths,
            String statusClass,
            String errorCode) {
        return event(
                operation,
                outcome,
                connectorId,
                groupId,
                AuditEvent.GROUP_RESOURCE_TYPE,
                changedPaths,
                statusClass,
                errorCode);
    }

    /**
     * The recorded path names for a set of changed Group attributes.
     *
     * <p>The mapping lives here rather than at the caller because the recorded strings are
     * this slice's vocabulary: a caller that assembled them would be a caller that could
     * assemble something else.
     */
    private static List<String> changedPaths(Set<AuditGroupAttribute> changed) {
        return changed.stream()
                .sorted()
                .flatMap(attribute -> switch (attribute) {
                    case DISPLAY_NAME -> GROUP_NAME_PATHS.stream();
                    case MEMBERS -> GROUP_MEMBER_PATHS.stream();
                    case EXTERNAL_ID -> EXTERNAL_ID_PATHS.stream();
                })
                .toList();
    }

    /**
     * A User lifecycle event: the actor is a connector, the resource type is
     * {@code User}, and the subject and the resource are the same id — the created
     * User, or {@code null} when there is no created User to name.
     */
    private AuditEvent userEvent(
            AuditOutcome outcome,
            UUID connectorId,
            UUID userId,
            String statusClass,
            String errorCode) {
        return event(
                AuditOperation.SCIM_USER_CREATE,
                outcome,
                connectorId,
                userId,
                AuditEvent.USER_RESOURCE_TYPE,
                NO_PATHS,
                statusClass,
                errorCode);
    }

    /**
     * Appends in a transaction of its own and swallows a failure, having raised an
     * operational alert for it.
     *
     * <p>{@link RuntimeException} and not {@link Throwable}: a persistence failure
     * arrives as one, and an {@link Error} means the process is in a state where
     * continuing to serve the request is not the right call either.
     */
    private void appendRaisingAlertOnFailure(AuditEvent event) {
        try {
            isolatedAppend.executeWithoutResult(status -> events.append(event));
        } catch (RuntimeException appendFailed) {
            alerts.auditAppendFailed(event.operation(), appendFailed.getClass());
        }
    }

    /**
     * An event about the login identity, which is a SCIM User: authentication, lockout and
     * administrative standing all name the same resource type as provisioning does, because
     * they now act on the same resource.
     */
    private AuditEvent event(
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            List<String> changedPaths,
            String statusClass,
            String errorCode) {
        return event(
                operation,
                outcome,
                actorId,
                subjectId,
                AuditEvent.USER_RESOURCE_TYPE,
                changedPaths,
                statusClass,
                errorCode);
    }

    /**
     * A connector or token lifecycle event: always a success, always named by the
     * connector's id, always on an administrative request that is returning 2xx.
     *
     * <p>Collapsing the five callers onto one builder rather than letting each pass
     * the outcome and status class is the point: those two fields are the same for
     * every one of them, and a per-caller argument is a per-caller opportunity to
     * record a refusal as a success.
     */
    private AuditEvent connectorEvent(
            AuditOperation operation,
            UUID actorId,
            UUID connectorId,
            List<String> changedPaths) {
        return event(
                operation,
                AuditOutcome.SUCCESS,
                actorId,
                connectorId,
                AuditEvent.CONNECTOR_RESOURCE_TYPE,
                changedPaths,
                AuditEvent.STATUS_OK,
                null);
    }

    /**
     * A token issue or rotation, granted or refused: named by the connector, carrying the
     * Permissions the token was given or asked for, by their sorted wire spelling. The spelling
     * is rendered here, inside the audit slice, from a closed enum — so the stored names cannot be
     * anything a caller wrote. A refusal is an escalation, changes nothing, and has one reason.
     */
    private AuditEvent tokenEvent(
            AuditOperation operation,
            boolean granted,
            UUID actorId,
            UUID connectorId,
            List<String> changedPaths,
            Set<Permission> permissions) {
        AuditRequest request = requests.current();
        return new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                operation,
                granted ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                actorId,
                connectorId,
                AuditEvent.CONNECTOR_RESOURCE_TYPE,
                connectorId,
                granted ? changedPaths : NO_PATHS,
                granted ? AuditEvent.STATUS_OK : AuditEvent.STATUS_CLIENT_ERROR,
                granted ? null : AuditAdministrativeRefusal.PERMISSION_ESCALATION.name(),
                request.method(),
                request.pathTemplate(),
                request.requestId(),
                null,
                null,
                null,
                permissions.stream().sorted(Permission.BY_VALUE).map(Permission::value).toList());
    }

    private AuditEvent event(
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            String resourceType,
            List<String> changedPaths,
            String statusClass,
            String errorCode) {
        return event(operation, outcome, actorId, subjectId, resourceType, changedPaths,
                statusClass, errorCode, null);
    }

    /** {@link #event} carrying a login method, which only a login event has. */
    private AuditEvent event(
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            String resourceType,
            List<String> changedPaths,
            String statusClass,
            String errorCode,
            String loginMethod) {
        AuditRequest request = requests.current();
        return new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                operation,
                outcome,
                actorId,
                subjectId,
                resourceType,
                subjectId,
                changedPaths,
                statusClass,
                errorCode,
                request.method(),
                request.pathTemplate(),
                request.requestId(),
                null,
                null,
                null,
                List.of(),
                loginMethod);
    }

    /**
     * A login event: one about the User the attempt named, carrying how the Login was attempted
     * (D15), rendered here from the closed {@link AuditLoginMethod} so the stored spelling is this
     * slice's own.
     */
    private AuditEvent loginEvent(
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            List<String> changedPaths,
            String statusClass,
            String errorCode,
            AuditLoginMethod method) {
        return event(operation, outcome, actorId, subjectId, AuditEvent.USER_RESOURCE_TYPE,
                changedPaths, statusClass, errorCode, method.value());
    }

    /**
     * Records an authorization refusal. Fail-open with an alert. The operation — method, route
     * template and correlation id — comes from the caller, because a chain-level refusal happens
     * before the dispatcher has matched a route, and before any request context this service's
     * other events read is guaranteed to exist.
     */
    @Override
    public void recordAccessDenied(UUID userId, AuditRequest operation) {
        appendRaisingAlertOnFailure(new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                AuditOperation.ACCESS_DENIED,
                AuditOutcome.FAILURE,
                userId,
                userId,
                AuditEvent.USER_RESOURCE_TYPE,
                userId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                INSUFFICIENT_PERMISSIONS,
                operation.method(),
                operation.pathTemplate(),
                operation.requestId(),
                null,
                null,
                null));
    }

    /**
     * Records an authorization refusal on the SCIM chain. Fail-open with an alert. The connector
     * is the actor and the subject, as a User is on the application chain's refusal, and the
     * reason is the same generic one.
     */
    @Override
    public void recordConnectorAccessDenied(UUID connectorId, AuditRequest operation) {
        appendRaisingAlertOnFailure(new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                AuditOperation.ACCESS_DENIED,
                AuditOutcome.FAILURE,
                connectorId,
                connectorId,
                AuditEvent.CONNECTOR_RESOURCE_TYPE,
                connectorId,
                NO_PATHS,
                AuditEvent.STATUS_CLIENT_ERROR,
                INSUFFICIENT_PERMISSIONS,
                operation.method(),
                operation.pathTemplate(),
                operation.requestId(),
                null,
                null,
                null));
    }

    /**
     * A bulk read: a success with no subject, carrying how many resources it returned and the
     * filter's shape. The shape is rendered here, inside the audit slice, from a value that has
     * nowhere to hold a literal — so the stored string is closed-vocabulary by construction.
     */
    private AuditEvent queryEvent(
            AuditOperation operation,
            UUID connectorId,
            String resourceType,
            int resultCount,
            AuditFilterShape filter) {
        AuditRequest request = requests.current();
        return new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                operation,
                AuditOutcome.SUCCESS,
                connectorId,
                null,
                resourceType,
                null,
                NO_PATHS,
                AuditEvent.STATUS_OK,
                null,
                request.method(),
                request.pathTemplate(),
                request.requestId(),
                resultCount,
                filter == null ? null : filter.render(),
                null);
    }
}
