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
 * @param role          the Role a membership change on a mapped Group granted or revoked, by its
 *                      name in the deployment's role mapping — configuration, never a value a
 *                      caller submitted — or {@code null} for every other operation
 * @param permissions   the Permissions a connector token was issued or rotated with or, on a
 *                      refused escalation, was asked for — sorted wire spellings from the closed
 *                      vocabulary in code; empty for every other operation
 * @param loginMethod   how a {@code LOGIN_SUCCESS} or {@code LOGIN_FAILURE} was attempted —
 *                      {@code password} or {@code sso}, rendered from {@link AuditLoginMethod}
 *                      (D15) — or {@code null} for every other operation
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
        String role,
        List<String> permissions,
        String loginMethod) {

    /**
     * A SCIM connector, as the audit trail names it.
     *
     * <p>A token lifecycle event carries this type and the CONNECTOR's id, not the
     * token's. The connector is what an administrator investigates and what
     * survives a rotation; a token id identifies a credential that may already be
     * gone, and grouping a connector's history by it would split one integration's
     * story across every token it ever held. Which token an event is about is the
     * operation plus the timestamp, on a stream that is append-only.
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
    }

    /** An event that names no login method, which is every event but a login's. */
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
            String filterShape,
            String role,
            List<String> permissions) {
        this(id, occurredAt, operation, outcome, actorId, subjectId, resourceType, resourceId,
                changedPaths, statusClass, errorCode, httpMethod, httpPath, requestId, resultCount,
                filterShape, role, permissions, null);
    }

    /** An event that names no Permissions, which is every event but a token's issue or rotation. */
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
            String filterShape,
            String role) {
        this(id, occurredAt, operation, outcome, actorId, subjectId, resourceType, resourceId,
                changedPaths, statusClass, errorCode, httpMethod, httpPath, requestId, resultCount,
                filterShape, role, List.of());
    }
}
