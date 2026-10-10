package com.example.backend.audit.domain;

import com.example.backend.authorization.domain.Role;
import com.example.backend.authorization.domain.Permission;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one way anything in this service records that something happened.
 *
 * <p><strong>No method here takes a {@link String}.</strong> That is the redaction
 * rule expressed as a signature rather than as a review note: a username, a
 * password and a bearer value are all strings, so a boundary that admits none
 * cannot be handed one by a caller who never read the rule. Every reference to a
 * person or a resource is a stable id, and every classification is a member of a
 * closed set. {@code ArchitectureTest.the_audit_trail_boundary_admits_no_free_text}
 * holds the shape, and {@code semgrep/rules/service-security.yml} flags a call
 * that tries to pass a credential-named value through it.
 *
 * <p>Which appends are fail-closed and which are fail-open with an operational
 * alert is documented on the implementation and in
 * {@code /docs/adr/0004-audit-append-failure-semantics.md}. It is stated there
 * rather than here because it is a property of how the recording is arranged, not
 * of what a caller asks for: a caller says what happened and does not choose
 * whether the trail may fail silently.
 */
public interface AuditTrail {

    /**
     * Records an accepted login that carries no MFA factor: a password Login's.
     *
     * @param method how the Login proved who was signing in (D15)
     */
    default void recordLoginSuccess(UUID accountId, AuditLoginMethod method) {
        recordLoginSuccess(accountId, method, null);
    }

    /**
     * Records an accepted login.
     *
     * @param method how the Login proved who was signing in (D15)
     * @param factor the MFA factor an Epic Login was made with (D17), or {@code null} for a
     *               Login that carries none
     */
    void recordLoginSuccess(UUID accountId, AuditLoginMethod method, AuditMfaFactor factor);

    /**
     * Records a refused login.
     *
     * @param subjectId stable id of the account the attempt named, or {@code null}
     *                  when the submitted username names no account — the one thing
     *                  that must not be recorded in its place
     * @param method    how the refused Login was attempted (D15)
     */
    void recordLoginFailure(UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method);

    /**
     * Records a refused login that counted toward no failure run — an Epic Login refused by
     * the login decision (D12), whose credential Epic checked rather than this service. The
     * same {@code LOGIN_FAILURE} as {@link #recordLoginFailure}, naming no changed path,
     * because nothing about the account changed.
     *
     * @param subjectId stable id of the account refused, or {@code null} when the attempt
     *                  named no acceptable account, so that no subject is recorded
     * @param method    how the refused Login was attempted (D15)
     */
    void recordLoginRefusal(UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method);

    /** Records a session ended by its holder. */
    void recordLogout(UUID accountId);

    /** Records a failure run reaching the configured limit. */
    void recordLockoutSet(UUID accountId);

    /**
     * Records an administrator ending a lockout.
     *
     * <p>The only lift there is, which is why it is the only one declared: a lock
     * does not expire, so there is no unrequested lift to record and no code path
     * that could record one. {@code actorId} is required by the signature for the
     * same reason — a lift that named no administrator would be describing
     * something this application cannot do.
     *
     * @param lockCause why the lifted lock had been imposed, or {@code null} when the User was not
     *                  locked — an Unlock still clears its failure run and restarts its dormancy
     *                  window
     */
    void recordLockoutLiftedByUnlock(UUID actorId, UUID subjectId, AuditLockCause lockCause);

    /**
     * Records a connector created.
     *
     * <p>Every method below identifies the event by the CONNECTOR's id and never by
     * the token's, and none of them can be handed a token value: the plaintext is a
     * {@code String}, and this boundary declares none. That is the "no plaintext
     * token in an event" requirement expressed the same way the "no userName in an
     * event" requirement is — as a signature rather than as a review note.
     */
    void recordConnectorCreated(UUID actorId, UUID connectorId);

    /**
     * Records a connector deleted, along with the tokens and aliases that went with
     * it.
     */
    void recordConnectorDeleted(UUID actorId, UUID connectorId);

    /**
     * Records a token minted for a connector, with the Permissions it carries.
     *
     * @param granted what the new token may do — the question an administrator reading the trail
     *                is asking of a credential
     */
    void recordConnectorTokenIssued(UUID actorId, UUID connectorId, Set<Permission> granted);

    /**
     * Records a connector's token replaced, the old one ending at the overlap, with the
     * Permissions the replacement carries.
     */
    void recordConnectorTokenRotated(UUID actorId, UUID connectorId, Set<Permission> granted);

    /**
     * Records a token issue refused because the administrator asked for a Permission it does not
     * hold itself. Fail-open with an alert, as every refusal is. Unlike an authorization refusal
     * this one DOES name Permissions — the ones requested — because an attempt to mint a
     * credential more powerful than oneself is exactly what an auditor looks for, and the caller
     * already knows what it asked for.
     *
     * @param requested every Permission the refused token would have carried
     */
    void recordConnectorTokenIssueRefused(
            UUID actorId, UUID connectorId, Set<Permission> requested);

    /** Records a rotation refused for the reason, and on the terms, an issue is. */
    void recordConnectorTokenRotateRefused(
            UUID actorId, UUID connectorId, Set<Permission> requested);

    /**
     * Records a connector token revoked.
     *
     * @param actorId the administrator who revoked it, or {@code null} when the
     *                revocation was part of deleting the connector — that event
     *                carries the actor, and repeating it here would suggest two
     *                separate administrative acts
     */
    void recordConnectorTokenRevoked(UUID actorId, UUID connectorId);

    /**
     * Records a connector creating a SCIM User.
     *
     * @param connectorId the acting connector
     * @param userId      the created resource's stable id
     */
    void recordScimUserCreated(UUID connectorId, UUID userId);

    /**
     * Records a User create refused — a {@code userName} a live User already holds
     * ({@code UNIQUENESS}), or a password the policy does not accept ({@code INVALID_VALUE}).
     *
     * <p>No subject id, because there is none: the resource was not created, and the
     * existing User that holds the name is not what the event is about. Naming it
     * would turn a refused create into an event against an unrelated identity's
     * history.
     *
     * @param connectorId the connector whose create was refused
     * @param reason      why, as the {@code scimType} the connector received
     */
    void recordScimUserCreateRejected(UUID connectorId, AuditScimRefusal reason);

    /**
     * Records a connector querying the User collection — a bulk read — through
     * {@code GET /Users} or {@code POST /Users/.search}.
     *
     * <p>Exactly one event per query that ran, whatever it asked for and whatever came back:
     * an empty result and a one-resource page are recorded like a full page, because a
     * zero-result probe and a directory sync are the two things the event exists to make
     * visible. A query refused before it runs — a malformed filter, a sort on an unknown
     * attribute — read nothing and is not recorded.
     *
     * <p>The count and the shape are numeric and closed-set, which is what keeps this boundary
     * free of text: the filter itself never crosses it.
     *
     * @param connectorId the connector that ran the query
     * @param resultCount how many resources the response carried
     * @param filter      the filter's shape, or {@code null} when the query had no filter
     */
    void recordScimUsersQueried(UUID connectorId, int resultCount, AuditFilterShape filter);

    /**
     * Records a connector creating a SCIM Group.
     *
     * @param groupId the created Group's stable id
     */
    void recordScimGroupCreated(UUID connectorId, UUID groupId);

    /**
     * Records a Group create refused.
     *
     * <p>No subject id, because there is none: the Group was not created, and the existing
     * resource that caused the refusal — a Group holding the name, a member id naming no
     * live User — is not what the event is about. Naming it would turn a refused create into
     * an event against an unrelated resource's history.
     */
    void recordScimGroupCreateRejected(UUID connectorId, AuditScimRefusal reason);

    /**
     * Records a connector changing a Group's name or membership. One operation for PUT and
     * PATCH, because the stored change is the same; which attributes moved is carried as the
     * event's changed paths.
     *
     * @param groupId the Group whose representation changed
     * @param changed which attributes moved — a closed set, because the recorded path list
     *                is a field readers filter on and a caller-assembled one is a place a
     *                submitted value could be written
     */
    void recordScimGroupReplaced(
            UUID connectorId, UUID groupId, Set<AuditGroupAttribute> changed);

    /**
     * Records a Group write refused — including an attempt to rename the Admin group or to
     * remove the Bootstrap Admin's membership of it, which carry
     * {@link AuditScimRefusal#MUTABILITY}.
     *
     * <p>This one DOES name the Group, unlike a refused create: the resource exists, the
     * write was aimed at it, and an attempt to provision the deployment's recovery authority
     * away is exactly the thing an administrator needs to find by that Group's id.
     */
    void recordScimGroupWriteRejected(
            UUID connectorId, UUID groupId, AuditScimRefusal reason);

    /** Records a connector deleting a Group. */
    void recordScimGroupDeleted(UUID connectorId, UUID groupId);

    /**
     * Records a connector querying the Group collection — {@code GET /Groups} or
     * {@code POST /Groups/.search} — for the reasons and on the terms
     * {@link #recordScimUsersQueried} is.
     */
    void recordScimGroupsQueried(UUID connectorId, int resultCount, AuditFilterShape filter);

    /**
     * Records a connector searching Users and Groups together — {@code POST /.search} — as one
     * bulk read. One event rather than one per type, because it was one request and one result
     * set: splitting it would make a single probe read as two.
     */
    void recordScimResourcesQueried(UUID connectorId, int resultCount, AuditFilterShape filter);

    /**
     * Records the server creating a resource it reserves for recovery, on a database that
     * did not have it.
     *
     * <p>No actor: seeding is the deployment establishing its own recovery path at startup,
     * not a principal acting, and inventing an actor for it would make the trail claim
     * somebody did this.
     *
     * <p>{@code group} rather than two methods, because the two seeded resources are one
     * act with one reason to exist — and a boolean that selects a closed-set resource type
     * keeps this boundary free of the {@code String} the type name would otherwise be.
     *
     * @param resourceId the seeded resource's stable id
     * @param group      whether the seeded resource is the Admin group rather than the
     *                   Bootstrap Admin User
     */
    void recordReservedResourceSeeded(UUID resourceId, boolean group);

    /**
     * Records that seeding put the Bootstrap Admin back into the Admin group, because something
     * outside SCIM had removed it.
     *
     * <p>A separate event from {@link #recordReservedResourceSeeded} because it is a different
     * fact: that one says a reserved resource was brought into existence, this one says a
     * deployment's administrative authority had been removed and startup restored it. Collapsing
     * them would make every restore read as a first-boot seed, which is the one reading that
     * would stop an operator investigating how the membership disappeared.
     *
     * <p>Audited at all because it is a WRITE that changes who holds Admin authority, and an
     * unaudited authority change is the gap this trail exists to close. It is also the only
     * authority change in the system with no actor — no principal asked for it, so inventing one
     * would make the trail claim somebody did this.
     *
     * @param groupId the Admin group's id
     * @param userId  the Bootstrap Admin whose membership was restored
     */
    void recordReservedMembershipRestored(UUID groupId, UUID userId);

    /**
     * Records a connector replacing or patching a User. Fail-closed: a change to an identity
     * this service cannot account for does not happen.
     *
     * @param changed which attributes moved; empty for a write that changed nothing
     */
    void recordScimUserReplaced(UUID connectorId, UUID userId, Set<AuditUserAttribute> changed);

    /**
     * Records a User write refused after the User was found — a reused password, a PATCH that
     * would remove a required attribute or found no target, a taken {@code userName}, or an
     * attempt to write the Bootstrap Admin. Fail-open with an alert, as every refusal is.
     */
    void recordScimUserWriteRejected(UUID connectorId, UUID userId, AuditScimRefusal reason);

    /**
     * Records a connector deleting a User. Fail-closed: a deletion this service cannot account
     * for does not happen.
     */
    void recordScimUserDeleted(UUID connectorId, UUID userId);

    /**
     * Records a User deletion refused after the User was found — an attempt to delete the
     * Bootstrap Admin. Fail-open with an alert, as every refusal is.
     */
    void recordScimUserDeleteRejected(UUID connectorId, UUID userId, AuditScimRefusal reason);

    /**
     * Records the outcome of a Session revocation — ending a User's sessions after a committed
     * change, whatever triggered it. Fail-open with an alert: it runs after the commit, so there is
     * no write left for a failed append to undo.
     *
     * @param actorId   who caused it: the connector whose write it followed, the administrator who
     *                  forced a password change — or {@code null} when the dormancy job, a Login
     *                  or the User's own self-service change did, none recorded as an actor
     * @param paths     the attributes whose change ended the sessions, as the changed paths; empty
     *                  when no attribute changed (a deletion, a lockout, a Login)
     * @param causes    why the sessions ended, recorded as the event's reason; never empty
     * @param succeeded whether the session store ended them
     */
    void recordUserSessionsRevoked(
            UUID actorId,
            UUID userId,
            Set<AuditUserAttribute> paths,
            Set<AuditSessionRevocationCause> causes,
            boolean succeeded);

    /**
     * Records the dormancy job locking a dormant User. Fail-closed: the append joins the job's
     * transaction, so a lock the trail cannot record is not imposed.
     *
     * <p>No actor parameter, because there is none: the scheduled job is not a principal, and the
     * operation is what says the job did it.
     */
    void recordDormancyLockout(UUID userId);

    /**
     * Records the dormancy job removing a dormant User's direct membership of every mapped Group
     * it held, as one event naming the User and the Roles it lost. Fail-closed, and actorless, for
     * the reasons {@link #recordDormancyLockout} is.
     *
     * @param roles the Roles the removed memberships conferred; never empty
     */
    void recordDormancyRoleRevocation(UUID userId, List<Role> roles);

    /**
     * Records a User gaining a mapped Group's Role because a connector's write added it to the
     * Group. Fail-closed: a change of power this service cannot account for does not happen.
     *
     * @param connectorId the connector whose write added the User
     * @param userId      the User that gained the Role, recorded as the subject
     * @param groupId     the mapped Group, recorded as the resource
     * @param role        the Role, recorded by its name in the role mapping
     */
    void recordRoleGranted(UUID connectorId, UUID userId, UUID groupId, Role role);

    /**
     * Records a User losing a mapped Group's Role because a connector's write removed it from the
     * Group — a PATCH, a PUT, or the Group's deletion. Fail-closed, for the reason
     * {@link #recordRoleGranted} is.
     */
    void recordRoleRevoked(UUID connectorId, UUID userId, UUID groupId, Role role);

    /**
     * Records an administrator requiring a password change of a User — directly, or by lifting its
     * lockout. Fail-closed: a requirement this service cannot account for is not imposed.
     */
    void recordPasswordChangeRequired(UUID actorId, UUID subjectId);

    /** Records a forced password change refused. Fail-open with an alert, as every refusal is. */
    void recordPasswordChangeRequirementRefused(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason);

    /** Records an Unlock refused. Fail-open with an alert, as every refusal is. */
    void recordUnlockRefused(UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason);

    /**
     * Records an authorization refusal on the application chain. Fail-open with an alert, as
     * every refusal is: the caller is already receiving {@code 403}.
     *
     * @param userId    the refused User's stable id, or {@code null} when the session names none
     * @param operation the refused request as the trail identifies one: its method, the route
     *                  TEMPLATE it addressed (never the resolved path) and its correlation id.
     *                  Passed in rather than read from the request context because a chain-level
     *                  refusal happens before the dispatcher has matched a route that context
     *                  could report.
     */
    void recordAccessDenied(UUID userId, AuditRequest operation);

    /**
     * Records an authorization refusal on the SCIM chain: a valid connector token lacking the
     * Permission the request needs. Fail-open with an alert, on the terms and with the one generic
     * reason {@link #recordAccessDenied} uses — never naming the Permission that was missing.
     *
     * @param connectorId the refused token's connector
     * @param operation   the refused request: method, route template, correlation id
     */
    void recordConnectorAccessDenied(UUID connectorId, AuditRequest operation);

    /**
     * Records a User replacing its own password. Fail-closed: a credential change this service
     * cannot account for does not happen. Names the changed paths, never a value.
     */
    void recordPasswordChanged(UUID userId);

    /**
     * Records a self-service password change refused. Fail-open with an alert: the caller is
     * already receiving a refusal, and that answer does not change.
     */
    void recordPasswordChangeRefused(UUID userId, AuditPasswordChangeRefusal reason);
}
