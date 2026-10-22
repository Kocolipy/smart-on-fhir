package com.example.backend.audit.domain;

import com.example.backend.authorization.domain.Permission;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stored rows the {@link Audited} events are, built the few ways an event is shaped.
 *
 * <p>Package-private: only the events build rows, so the textual fields — the resource type, the
 * status class, the error code, the rendered Permissions, method and factor — are spelled here,
 * from the closed sets the events carry, and nowhere a caller could reach.
 */
final class AuditRows {

    /**
     * The one reason an authorization refusal is recorded with, whichever rule refused: generic,
     * so the trail never names a Permission, a Role or the policy.
     */
    static final String INSUFFICIENT_PERMISSIONS = "INSUFFICIENT_PERMISSIONS";

    /**
     * What a change of a Group's membership records as changed — a connector's write, the
     * Permissions moving with it, a Group's deletion, a restored reserved membership — spelled once.
     */
    static final List<String> MEMBERSHIP_PATHS = List.of(AuditGroupAttribute.MEMBERS.path());

    /** The Permissions of every event but a token's issue or rotation: none. */
    private static final List<String> NO_PERMISSIONS = List.of();

    private AuditRows() {
    }

    /**
     * What one event says happened, whatever shape its row takes: the operation and its outcome,
     * who acted on whom, what changed, and how the request ended.
     *
     * @param errorCode the event's reason, or {@code null} when it has none
     */
    record Change(
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            List<String> changedPaths,
            String statusClass,
            String errorCode) {

        /** A success on a request that answered 2xx, with no reason to record. */
        static Change success(
                AuditOperation operation, UUID actorId, UUID subjectId, List<String> changedPaths) {
            return new Change(operation, AuditOutcome.SUCCESS, actorId, subjectId, changedPaths,
                    AuditEvent.STATUS_OK, null);
        }

        /** A refusal: a client error that changed nothing, recorded with its one reason. */
        static Change refusal(
                AuditOperation operation, UUID actorId, UUID subjectId, Enum<?> reason) {
            return refusal(operation, actorId, subjectId, reason, Audited.NO_PATHS);
        }

        /**
         * A refusal that still moved something — a wrong current password lengthens the failure
         * run — recorded with its one reason and the paths it moved.
         */
        static Change refusal(AuditOperation operation, UUID actorId, UUID subjectId,
                Enum<?> reason, List<String> changedPaths) {
            return new Change(operation, AuditOutcome.FAILURE, actorId, subjectId,
                    changedPaths, AuditEvent.STATUS_CLIENT_ERROR, reason.name());
        }
    }

    /**
     * The connector tokens a token event concerns, by their stable ids: the one it is about, and,
     * on a rotation, the replacement minted for it.
     *
     * @param concerned   the token issued, rotated or revoked, or the one a refused rotation
     *                    addressed; {@code null} when no token was addressed (a refused issue)
     * @param replacement the token a rotation minted, or {@code null} for every other event
     */
    record Tokens(UUID concerned, UUID replacement) {

        /** What every event but a token's names: no token at all. */
        static final Tokens NONE = new Tokens(null, null);

        /** One token, and no replacement. */
        static Tokens one(UUID concerned) {
            return new Tokens(concerned, null);
        }
    }

    /**
     * What a membership change did to a User's power: the Permissions before and after, the
     * Superuser marker, and — on the dormancy job's revocation — the Groups removed.
     *
     * @param superuserChanged {@code null} for every event that is no membership change
     */
    private record Membership(
            List<String> permissionsBefore,
            List<String> permissionsAfter,
            Boolean superuserChanged,
            List<UUID> groupIds) {

        /** What every event but a membership change fills: nothing. */
        static final Membership NONE = new Membership(List.of(), List.of(), null, List.of());

        static Membership of(AuditPermissionChange change, List<UUID> groupIds) {
            return new Membership(change.beforeValues(), change.afterValues(),
                    change.superuserChanged(), groupIds);
        }
    }

    /**
     * The fields only some events fill: a bulk read's count and filter shape, a token's
     * Permissions and ids, a Login's method and MFA factor, and a membership change's Permissions.
     */
    private record Particulars(
            Integer resultCount,
            String filterShape,
            List<String> permissions,
            String loginMethod,
            String mfaFactor,
            Tokens tokens,
            Membership membership) {

        /** What every other event fills: nothing. */
        static final Particulars NONE = new Particulars(
                null, null, NO_PERMISSIONS, null, null, Tokens.NONE, Membership.NONE);

        static Particulars membership(Membership membership) {
            return new Particulars(
                    null, null, NO_PERMISSIONS, null, null, Tokens.NONE, membership);
        }
    }

    /**
     * An event about the login identity, which is a SCIM User: authentication, lockout and
     * administrative standing all name the same resource type as provisioning does, because they
     * act on the same resource.
     */
    static AuditEvent user(Audited.Stamp stamp, Change change) {
        return resource(stamp, change, AuditEvent.USER_RESOURCE_TYPE);
    }

    /**
     * A Group lifecycle event: the actor is a connector and the resource type is {@code Group},
     * whose id is also the subject — or {@code null} when the write produced no Group to name.
     */
    static AuditEvent group(Audited.Stamp stamp, Change change) {
        return resource(stamp, change, AuditEvent.GROUP_RESOURCE_TYPE);
    }

    /**
     * A connector or token lifecycle event: always a success, always named by the connector's id,
     * always on an administrative request that is returning 2xx — fixed here rather than passed,
     * because a per-caller argument is a per-caller opportunity to record a refusal as a success.
     */
    static AuditEvent connector(
            Audited.Stamp stamp,
            AuditOperation operation,
            UUID actorId,
            UUID connectorId,
            List<String> changedPaths) {
        return resource(stamp, Change.success(operation, actorId, connectorId, changedPaths),
                AuditEvent.CONNECTOR_RESOURCE_TYPE);
    }

    /**
     * An event whose subject is also its resource, of the given type, naming nothing beyond the
     * change itself.
     */
    static AuditEvent resource(Audited.Stamp stamp, Change change, String resourceType) {
        return row(stamp, stamp.requests().current(), change, resourceType, change.subjectId(),
                Particulars.NONE);
    }

    /**
     * A login event: one about the User the attempt named, carrying how the Login was attempted
     * (D15) and, on an Epic success, the MFA factor (D17), each rendered from its closed set.
     *
     * @param factor the MFA factor, or {@code null} for every event but an Epic success
     */
    static AuditEvent login(
            Audited.Stamp stamp, Change change, AuditLoginMethod method, AuditMfaFactor factor) {
        return row(stamp, stamp.requests().current(), change, AuditEvent.USER_RESOURCE_TYPE,
                change.subjectId(), new Particulars(null, null, NO_PERMISSIONS,
                        method.value(), factor == null ? null : factor.value(), Tokens.NONE,
                        Membership.NONE));
    }

    /**
     * The {@code LOGIN_FAILURE} every refused login shares, differing only in what it says changed
     * about the account: no actor, the attempt's subject, its reason and its method.
     */
    static AuditEvent loginFailure(
            Audited.Stamp stamp,
            UUID subjectId,
            AuditRefusalReason reason,
            AuditLoginMethod method,
            List<String> changedPaths) {
        return login(stamp, new Change(AuditOperation.LOGIN_FAILURE, AuditOutcome.FAILURE, null,
                subjectId, changedPaths, AuditEvent.STATUS_CLIENT_ERROR, reason.name()),
                method, null);
    }

    /**
     * A token issue, rotation or revocation, granted or refused: named by the connector as subject
     * and resource and by the tokens it concerns, carrying the Permissions the token was given or
     * asked for, by their sorted wire spelling — none for a revocation.
     *
     * @param change the event, whose subject is the connector
     * @param tokens the token it concerns and, on a rotation, its replacement
     */
    static AuditEvent token(
            Audited.Stamp stamp, Change change, Tokens tokens, Set<Permission> permissions) {
        return row(stamp, stamp.requests().current(), change, AuditEvent.CONNECTOR_RESOURCE_TYPE,
                change.subjectId(), new Particulars(null, null,
                        Permission.valuesOf(permissions),
                        null, null, tokens, Membership.NONE));
    }

    /**
     * A bulk read: a success with no subject, carrying how many resources it returned and the
     * filter's shape, rendered from a value that has nowhere to hold a literal.
     */
    static AuditEvent query(
            Audited.Stamp stamp,
            AuditOperation operation,
            UUID connectorId,
            String resourceType,
            int resultCount,
            AuditFilterShape filter) {
        return row(stamp, stamp.requests().current(),
                Change.success(operation, connectorId, null, Audited.NO_PATHS), resourceType, null,
                new Particulars(resultCount, filter == null ? null : filter.render(),
                        NO_PERMISSIONS, null, null, Tokens.NONE, Membership.NONE));
    }

    /**
     * A change of power through a Group's membership: the subject is the User whose power changed,
     * the resource the Group whose membership moved, and the User's effective Permissions before
     * and after are named — the one event whose subject and resource differ, because it is about
     * both.
     */
    static AuditEvent permissionChange(
            Audited.Stamp stamp,
            AuditOperation operation,
            UUID connectorId,
            UUID userId,
            UUID groupId,
            AuditPermissionChange change) {
        return row(stamp, stamp.requests().current(),
                Change.success(operation, connectorId, userId, MEMBERSHIP_PATHS),
                AuditEvent.GROUP_RESOURCE_TYPE, groupId,
                Particulars.membership(Membership.of(change, List.of())));
    }

    /**
     * The dormancy job's membership revocation: the User as subject and resource, {@code groups}
     * as the changed path, every Group removed — by id, sorted and distinct — and the User's
     * effective Permissions before and after.
     */
    static AuditEvent dormancyMembershipRevocation(
            Audited.Stamp stamp, UUID userId, List<UUID> groupIds, AuditPermissionChange change) {
        return row(stamp, stamp.requests().current(),
                Change.success(AuditOperation.DORMANCY_MEMBERSHIP_REVOCATION, null, userId,
                        AuditAttribute.paths(Set.of(AuditUserAttribute.GROUPS))),
                AuditEvent.USER_RESOURCE_TYPE, userId,
                Particulars.membership(Membership.of(change, groupIds.stream().distinct()
                        .sorted(Comparator.comparing(UUID::toString)).toList())));
    }

    /**
     * An authorization refusal: the refused principal as actor, subject and resource, the one
     * generic reason, and the request it CARRIES — a chain-level refusal happens before the
     * dispatcher has matched a route, and before any request context the other events read is
     * guaranteed to exist, so the context is not read at all.
     */
    static AuditEvent accessDenied(
            Audited.Stamp stamp, UUID refusedId, String resourceType, AuditRequest operation) {
        return row(stamp, operation,
                new Change(AuditOperation.ACCESS_DENIED, AuditOutcome.FAILURE, refusedId, refusedId,
                        Audited.NO_PATHS, AuditEvent.STATUS_CLIENT_ERROR, INSUFFICIENT_PERMISSIONS),
                resourceType, refusedId, Particulars.NONE);
    }

    /** A revocation's causes as its reason: their names, in declaration order, comma-joined. */
    static String causeNames(Set<AuditSessionRevocationCause> causes) {
        return causes.stream()
                .sorted()
                .map(AuditSessionRevocationCause::name)
                .collect(Collectors.joining(","));
    }

    /** The one place a row is assembled: the stamp, the request served, the change, the rest. */
    private static AuditEvent row(
            Audited.Stamp stamp,
            AuditRequest request,
            Change change,
            String resourceType,
            UUID resourceId,
            Particulars particulars) {
        // A false positive of be-audit-sensitive-value: `particulars.tokens()` carries a connector
        // token's stable UUID id, never its value or digest — no event holds a String, so none
        // could be handed one — and naming the token by id is what a token event must do.
        return new AuditEvent( // nosemgrep: be-audit-sensitive-value
                stamp.id(),
                stamp.occurredAt(),
                change.operation(),
                change.outcome(),
                change.actorId(),
                change.subjectId(),
                resourceType,
                resourceId,
                change.changedPaths(),
                change.statusClass(),
                change.errorCode(),
                request.method(),
                request.pathTemplate(),
                request.requestId(),
                particulars.resultCount(),
                particulars.filterShape(),
                particulars.permissions(),
                particulars.loginMethod(),
                particulars.mfaFactor(),
                particulars.tokens().concerned(),
                particulars.tokens().replacement(),
                particulars.membership().permissionsBefore(),
                particulars.membership().permissionsAfter(),
                particulars.membership().superuserChanged(),
                particulars.membership().groupIds());
    }
}
