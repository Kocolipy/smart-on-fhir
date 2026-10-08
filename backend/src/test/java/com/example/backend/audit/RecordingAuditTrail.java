package com.example.backend.audit;

import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditFilterShape;
import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditLockCause;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditScimRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An {@link AuditTrail} that keeps what it was told, so a unit test of a use case
 * can assert which events that use case records without a database.
 *
 * <p>Records the arguments rather than counting calls: "exactly one event, naming
 * this account by its stable id, for this reason" is the claim worth checking, and
 * a counter cannot express it.
 */
public final class RecordingAuditTrail implements AuditTrail {

    private static String operationOf(com.example.backend.audit.domain.AuditRequest request) {
        return request.method() + " " + request.pathTemplate();
    }

    private final List<Recorded> recorded = new ArrayList<>();

    private final List<AuditLoginMethod> loginMethods = new ArrayList<>();

    private final List<AuditMfaFactor> mfaFactors = new ArrayList<>();

    /** One recorded call: what it said happened, and the ids and reason it named. */
    public record Recorded(
            AuditOperation operation, UUID actorId, UUID subjectId, String detail) {
    }

    public List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    /** Every recorded call of one operation, for a per-operation count. */
    public List<Recorded> of(AuditOperation operation) {
        return recorded.stream().filter(event -> event.operation() == operation).toList();
    }

    /**
     * The login method of every recorded {@code LOGIN_SUCCESS} and {@code LOGIN_FAILURE}, in the
     * order they were recorded. Kept beside {@link #recorded()} rather than in {@link Recorded},
     * so a test's expected {@code Recorded} values need not change for a field it is not about.
     */
    public List<AuditLoginMethod> loginMethods() {
        return List.copyOf(loginMethods);
    }

    /**
     * The MFA factor of every recorded {@code LOGIN_SUCCESS}, in order, {@code null} where it
     * carried none — kept beside {@link #recorded()} for the reason {@link #loginMethods()} is.
     */
    public List<AuditMfaFactor> mfaFactors() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(mfaFactors));
    }

    public void reset() {
        recorded.clear();
        loginMethods.clear();
        mfaFactors.clear();
    }

    @Override
    public void recordLoginSuccess(
            UUID accountId, AuditLoginMethod method, AuditMfaFactor factor) {
        recorded.add(new Recorded(AuditOperation.LOGIN_SUCCESS, accountId, accountId, null));
        loginMethods.add(method);
        mfaFactors.add(factor);
    }

    @Override
    public void recordLoginFailure(
            UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method) {
        recorded.add(new Recorded(
                AuditOperation.LOGIN_FAILURE, null, subjectId, reason.name()));
        loginMethods.add(method);
    }

    @Override
    public void recordLoginRefusal(
            UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method) {
        recordLoginFailure(subjectId, reason, method);
    }

    @Override
    public void recordLogout(UUID accountId) {
        recorded.add(new Recorded(AuditOperation.LOGOUT, accountId, accountId, null));
    }

    @Override
    public void recordLockoutSet(UUID accountId) {
        recorded.add(new Recorded(AuditOperation.LOCKOUT_SET, null, accountId, null));
    }

    /** The detail is the lifted lock's cause, or {@code null} when none stood. */
    @Override
    public void recordLockoutLiftedByUnlock(
            UUID actorId, UUID subjectId, AuditLockCause lockCause) {
        recorded.add(new Recorded(AuditOperation.LOCKOUT_LIFT, actorId, subjectId,
                lockCause == null ? null : lockCause.name()));
    }

    @Override
    public void recordConnectorCreated(UUID actorId, UUID connectorId) {
        recorded.add(new Recorded(AuditOperation.CONNECTOR_CREATE, actorId, connectorId, null));
    }

    @Override
    public void recordConnectorDeleted(UUID actorId, UUID connectorId) {
        recorded.add(new Recorded(AuditOperation.CONNECTOR_DELETE, actorId, connectorId, null));
    }

    @Override
    public void recordConnectorTokenIssued(
            UUID actorId, UUID connectorId, Set<Permission> granted) {
        recorded.add(new Recorded(
                AuditOperation.CONNECTOR_TOKEN_ISSUE, actorId, connectorId, names(granted)));
    }

    @Override
    public void recordConnectorTokenRotated(
            UUID actorId, UUID connectorId, Set<Permission> granted) {
        recorded.add(new Recorded(
                AuditOperation.CONNECTOR_TOKEN_ROTATE, actorId, connectorId, names(granted)));
    }

    @Override
    public void recordConnectorTokenIssueRefused(
            UUID actorId, UUID connectorId, Set<Permission> requested) {
        recorded.add(new Recorded(AuditOperation.CONNECTOR_TOKEN_ISSUE, actorId, connectorId,
                AuditAdministrativeRefusal.PERMISSION_ESCALATION.name() + " " + names(requested)));
    }

    @Override
    public void recordConnectorTokenRotateRefused(
            UUID actorId, UUID connectorId, Set<Permission> requested) {
        recorded.add(new Recorded(AuditOperation.CONNECTOR_TOKEN_ROTATE, actorId, connectorId,
                AuditAdministrativeRefusal.PERMISSION_ESCALATION.name() + " " + names(requested)));
    }

    /** Permissions by their sorted wire spelling, comma-joined: {@code group:read,user:read}. */
    private static String names(Set<Permission> permissions) {
        return permissions.stream().sorted(Permission.BY_VALUE).map(Permission::value)
                .collect(Collectors.joining(","));
    }

    @Override
    public void recordConnectorTokenRevoked(UUID actorId, UUID connectorId) {
        recorded.add(new Recorded(
                AuditOperation.CONNECTOR_TOKEN_REVOKE, actorId, connectorId, null));
    }

    @Override
    public void recordScimUserCreated(UUID connectorId, UUID userId) {
        recorded.add(new Recorded(AuditOperation.SCIM_USER_CREATE, connectorId, userId, null));
    }

    @Override
    public void recordScimUserCreateRejected(UUID connectorId, AuditScimRefusal reason) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_USER_CREATE,
                connectorId,
                null,
                reason.name()));
    }

    @Override
    public void recordScimUsersQueried(UUID connectorId, int resultCount, AuditFilterShape filter) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_USER_LIST, connectorId, null, queryDetail(resultCount, filter)));
    }

    /** A bulk read's count and rendered shape, e.g. {@code 2 userName eq ?}, as the detail. */
    private static String queryDetail(int resultCount, AuditFilterShape filter) {
        return resultCount + (filter == null ? "" : " " + filter.render());
    }

    @Override
    public void recordScimGroupCreated(UUID connectorId, UUID groupId) {
        recorded.add(new Recorded(AuditOperation.SCIM_GROUP_CREATE, connectorId, groupId, null));
    }

    @Override
    public void recordScimGroupCreateRejected(UUID connectorId, AuditScimRefusal reason) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_GROUP_CREATE, connectorId, null, reason.name()));
    }

    /**
     * Records the changed attributes as the detail, sorted, so a test can assert WHICH
     * attributes a write reported as moved — the claim worth checking about a Group write is
     * that a no-op PUT reports nothing, and a count cannot express that.
     */
    @Override
    public void recordScimGroupReplaced(
            UUID connectorId, UUID groupId, Set<AuditGroupAttribute> changed) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_GROUP_REPLACE,
                connectorId,
                groupId,
                changed.stream().map(Enum::name).sorted().collect(Collectors.joining(","))));
    }

    @Override
    public void recordScimGroupWriteRejected(
            UUID connectorId, UUID groupId, AuditScimRefusal reason) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_GROUP_REPLACE, connectorId, groupId, reason.name()));
    }

    @Override
    public void recordScimGroupDeleted(UUID connectorId, UUID groupId) {
        recorded.add(new Recorded(AuditOperation.SCIM_GROUP_DELETE, connectorId, groupId, null));
    }

    @Override
    public void recordScimGroupsQueried(UUID connectorId, int resultCount, AuditFilterShape filter) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_GROUP_LIST, connectorId, null, queryDetail(resultCount, filter)));
    }

    @Override
    public void recordScimResourcesQueried(
            UUID connectorId, int resultCount, AuditFilterShape filter) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_RESOURCE_LIST, connectorId, null,
                queryDetail(resultCount, filter)));
    }

    /**
     * Records the seeded resource's KIND as the detail, because that is the one thing a
     * seeding test needs to tell the two events apart — both name the server as no actor.
     */
    @Override
    public void recordReservedResourceSeeded(UUID resourceId, boolean group) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_RESOURCE_SEED,
                null,
                resourceId,
                group ? "Group" : "User"));
    }

    @Override
    public void recordReservedMembershipRestored(UUID groupId, UUID userId) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_RESOURCE_SEED, null, groupId, "members-restored " + userId));
    }

    /** The detail is the sorted changed attribute names, comma-joined; empty for a no-op. */
    @Override
    public void recordScimUserReplaced(
            UUID connectorId, UUID userId, Set<AuditUserAttribute> changed) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_USER_REPLACE, connectorId, userId, joined(changed)));
    }

    @Override
    public void recordScimUserWriteRejected(
            UUID connectorId, UUID userId, AuditScimRefusal reason) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_USER_REPLACE, connectorId, userId, reason.name()));
    }

    @Override
    public void recordScimUserDeleted(UUID connectorId, UUID userId) {
        recorded.add(new Recorded(AuditOperation.SCIM_USER_DELETE, connectorId, userId, null));
    }

    @Override
    public void recordScimUserDeleteRejected(
            UUID connectorId, UUID userId, AuditScimRefusal reason) {
        recorded.add(new Recorded(
                AuditOperation.SCIM_USER_DELETE, connectorId, userId, reason.name()));
    }

    /** The detail is the outcome, then the causes: {@code "SUCCESS:ACTIVE,PASSWORD"}. */
    @Override
    public void recordUserSessionsRevoked(
            UUID connectorId, UUID userId, Set<AuditUserAttribute> causes, boolean succeeded) {
        recorded.add(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE,
                connectorId,
                userId,
                (succeeded ? "SUCCESS:" : "FAILURE:") + joined(causes)));
    }

    @Override
    public void recordDormancyLockout(UUID userId) {
        recorded.add(new Recorded(AuditOperation.DORMANCY_LOCKOUT, null, userId, null));
    }

    /** The detail is the Roles lost, by name, in the order given: {@code "Account admin,Superuser"}. */
    @Override
    public void recordDormancyRoleRevocation(UUID userId, List<Role> roles) {
        recorded.add(new Recorded(AuditOperation.DORMANCY_ROLE_REVOCATION, null, userId,
                roles.stream().map(Role::name).collect(Collectors.joining(","))));
    }

    /** The detail is the Group then the Role: {@code "<groupId>:Account admin"}. */
    @Override
    public void recordRoleGranted(UUID connectorId, UUID userId, UUID groupId, Role role) {
        recorded.add(new Recorded(
                AuditOperation.ROLE_GRANT, connectorId, userId, groupId + ":" + role.name()));
    }

    /** The detail is the Group then the Role, as {@link #recordRoleGranted}'s is. */
    @Override
    public void recordRoleRevoked(UUID connectorId, UUID userId, UUID groupId, Role role) {
        recorded.add(new Recorded(
                AuditOperation.ROLE_REVOKE, connectorId, userId, groupId + ":" + role.name()));
    }

    @Override
    public void recordPasswordChangeRequired(UUID actorId, UUID subjectId) {
        recorded.add(new Recorded(
                AuditOperation.PASSWORD_CHANGE_REQUIRE, actorId, subjectId, null));
    }

    @Override
    public void recordPasswordChangeRequirementRefused(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason) {
        recorded.add(new Recorded(
                AuditOperation.PASSWORD_CHANGE_REQUIRE, actorId, subjectId, reason.name()));
    }

    @Override
    public void recordUnlockRefused(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason) {
        recorded.add(new Recorded(AuditOperation.LOCKOUT_LIFT, actorId, subjectId, reason.name()));
    }

    @Override
    public void recordAccessDenied(
            UUID userId, com.example.backend.audit.domain.AuditRequest operation) {
        accessDeniedRequests.add(operation);
        recorded.add(new Recorded(AuditOperation.ACCESS_DENIED, userId, userId,
                operationOf(operation)));
    }

    private final List<com.example.backend.audit.domain.AuditRequest> accessDeniedRequests =
            new ArrayList<>();

    @Override
    public void recordConnectorAccessDenied(
            UUID connectorId, com.example.backend.audit.domain.AuditRequest operation) {
        accessDeniedRequests.add(operation);
        recorded.add(new Recorded(AuditOperation.ACCESS_DENIED, connectorId, connectorId,
                operationOf(operation)));
    }

    /** The operation each authorization refusal was recorded with, request id included. */
    public List<com.example.backend.audit.domain.AuditRequest> accessDeniedRequests() {
        return List.copyOf(accessDeniedRequests);
    }

    @Override
    public void recordPasswordChanged(UUID userId) {
        recorded.add(new Recorded(AuditOperation.PASSWORD_CHANGE, userId, userId, null));
    }

    @Override
    public void recordPasswordChangeRefused(UUID userId, AuditPasswordChangeRefusal reason) {
        recorded.add(new Recorded(AuditOperation.PASSWORD_CHANGE, userId, userId, reason.name()));
    }

    private static String joined(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
