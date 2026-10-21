package com.example.backend.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recorded thing that happened, as the audit trail holds it.
 *
 * <p>Every reference to a person or a resource is a {@link UUID} — the account's
 * stable, non-reassignable id — and there is deliberately no field a username, a
 * password or a bearer value could be written into. That is the redaction rule
 * expressed as a shape rather than as a review note: {@code actorId} and
 * {@code subjectId} are ids because the readable identifiers are exactly what
 * must not be retained, and the remaining {@code String} fields carry values from
 * closed vocabularies the audit slice owns (a status class, an error code that is
 * a reason name, a route template) rather than anything a caller submitted.
 *
 * <p>An event is never updated and never deleted except by retention. Nothing
 * here offers a {@code with...} transition for that reason: correcting a recorded
 * event is not a capability, and the database refuses the statement that would.
 *
 * @param id            the event's own identity, assigned before the insert
 * @param occurredAt    when the audited operation happened
 * @param operation     what happened
 * @param outcome       whether the operation worked
 * @param actorId       stable id of whoever acted — an account for an
 *                      administrative or authentication event, a CONNECTOR for a
 *                      SCIM one — or {@code null} when nobody was authenticated
 * @param subjectId     stable id of the resource acted on, or {@code null} when
 *                      the operation named no existing one
 * @param resourceType  the kind of resource acted on
 * @param resourceId    stable id of the resource acted on, normally the same as
 *                      {@code subjectId} while accounts are the only resource
 * @param changedPaths  attribute paths the operation changed, from a fixed
 *                      vocabulary; empty when it changed nothing
 * @param statusClass   how the triggering request ended, classified
 * @param errorCode     a reason name from this service's own code, or
 *                      {@code null}; never a message
 * @param httpMethod    method of the triggering request, or {@code null} when it
 *                      was not one
 * @param httpPath      matched route TEMPLATE of the triggering request, never
 *                      the resolved path — that carries the username in it
 * @param requestId     correlation id minted for the triggering request
 * @param resultCount   how many resources a bulk read returned; {@code null} for every
 *                      other operation
 * @param filterShape   a bulk read's filter as its shape — canonical paths and operators,
 *                      {@code ?} for every value — or {@code null} when it had none or the
 *                      operation is not a read. Rendered from {@link AuditFilterShape}, so
 *                      no literal can be in it
 * @param permissions   the Permissions a connector token was issued or rotated with — sorted
 *                      wire spellings from the closed vocabulary in code; empty for every other
 *                      operation
 * @param loginMethod   how a {@code LOGIN_SUCCESS} or {@code LOGIN_FAILURE} was attempted —
 *                      {@code password} or {@code sso}, rendered from {@link AuditLoginMethod}
 *                      (D15) — or {@code null} for every other operation
 * @param mfaFactor     the MFA factor an Epic {@code LOGIN_SUCCESS} was made with (D17) —
 *                      {@code idp-attested} while the Epic organisation's MFA is attested, or the
 *                      factor its {@code amr} named — rendered from {@link AuditMfaFactor}, or
 *                      {@code null} for every other event, a password Login's included
 * @param tokenId       the stable id of the connector token a token event concerns — the one
 *                      issued, rotated or revoked, or the one a refused rotation addressed — or
 *                      {@code null} for every other event and a refused issue, which addressed
 *                      none. An id, never the token's value or digest
 * @param replacementTokenId the stable id of the token a rotation minted to replace
 *                      {@code tokenId}, or {@code null} for every other event
 * @param permissionsBefore the subject User's effective Permissions before a membership change —
 *                      a {@code PERMISSION_GRANT}, {@code PERMISSION_REVOKE} or
 *                      {@code DORMANCY_MEMBERSHIP_REVOCATION} — as sorted wire spellings from the
 *                      closed vocabulary in code; empty for every other event
 * @param permissionsAfter the same User's effective Permissions after the change; empty for every
 *                      other event
 * @param superuserChanged whether a membership change granted or removed the Superuser, or
 *                      {@code null} for every event that is no membership change
 * @param groupIds      every Group a {@code DORMANCY_MEMBERSHIP_REVOCATION} removed the User
 *                      from, by stable id, sorted and distinct; empty for every other event — a
 *                      connector's membership change names its one Group as the resource
 */
public record AuditEvent(
        UUID id,
        Instant occurredAt,
        AuditOperation operation,
        AuditOutcome outcome,
        UUID actorId,
        UUID subjectId,
        String resourceType,
        UUID resourceId,
        List<String> changedPaths,
        String statusClass,
        String errorCode,
        String httpMethod,
        String httpPath,
        String requestId,
        Integer resultCount,
        String filterShape,
        List<String> permissions,
        String loginMethod,
        String mfaFactor,
        UUID tokenId,
        UUID replacementTokenId,
        List<String> permissionsBefore,
        List<String> permissionsAfter,
        Boolean superuserChanged,
        List<UUID> groupIds) {

    /**
     * A SCIM connector, as the audit trail names it.
     *
     * <p>A token lifecycle event carries this type and the CONNECTOR's id as its subject and
     * resource. The connector is what an administrator investigates and what survives a
     * rotation, and grouping a connector's history by a token id would split one
     * integration's story across every token it ever held. Which token an event is about is
     * named beside it, in {@link #tokenId} (and, on a rotation, {@link #replacementTokenId}),
     * so two rotations of one token — or a refusal of a token already gone — can be told apart.
     */
    public static final String CONNECTOR_RESOURCE_TYPE = "ScimConnector";

    /**
     * A SCIM User, spelled as RFC 7643 spells the resource type.
     *
     * <p>The login identity as well as the provisioned resource, because they are now the
     * same thing: the account aggregate is gone and a SCIM User owns the profile and the
     * authentication state together. There was a separate {@code "Account"} type while both
     * existed, and its own documentation said it should collapse into this one once the two
     * identities became one — this is that collapse. An authentication event and a
     * provisioning event about the same person therefore group under one resource type and
     * one id, which is the whole point of having unified them.
     */
    public static final String USER_RESOURCE_TYPE = "User";

    /**
     * A SCIM Group, spelled as RFC 7643 spells the resource type.
     *
     * <p>Carries the Group's own id, including on an event about a membership: a membership
     * has no id of its own, and the resource whose representation changed is the Group.
     * Which User was added or removed is not recorded — a membership change is an event
     * about authority, and naming the other party would put a second identity's history
     * inside the first one's event.
     */
    public static final String GROUP_RESOURCE_TYPE = "Group";

    /**
     * Both resource types at once: the resource type of a base {@code /.search}, which spans
     * Users and Groups in one request and one result set.
     */
    public static final String USER_AND_GROUP_RESOURCE_TYPE = "User,Group";

    /** The request succeeded. */
    public static final String STATUS_OK = "ok";

    /** The request was refused because of what it was or who made it. */
    public static final String STATUS_CLIENT_ERROR = "client_error";

    /** The request failed inside this service. */
    public static final String STATUS_SERVER_ERROR = "server_error";

    public AuditEvent {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        permissionsBefore = permissionsBefore == null ? List.of() : List.copyOf(permissionsBefore);
        permissionsAfter = permissionsAfter == null ? List.of() : List.copyOf(permissionsAfter);
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
    }

    /**
     * An event that names no Permissions, no login method, no token and no membership change,
     * which is every event but a token's, a login's and a membership change's.
     */
    public AuditEvent(
            UUID id,
            Instant occurredAt,
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            String resourceType,
            UUID resourceId,
            List<String> changedPaths,
            String statusClass,
            String errorCode,
            String httpMethod,
            String httpPath,
            String requestId,
            Integer resultCount,
            String filterShape) {
        this(id, occurredAt, operation, outcome, actorId, subjectId, resourceType, resourceId,
                changedPaths, statusClass, errorCode, httpMethod, httpPath, requestId, resultCount,
                filterShape, List.of(), null, null, null, null, List.of(), List.of(), null,
                List.of());
    }
}
