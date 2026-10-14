package com.example.backend.scim.controller;

import com.example.backend.audit.domain.AuditScimRefusal;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.RequestActor;
import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.InvalidPreconditionException;
import com.example.backend.scim.domain.InvalidScimFilterException;
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.PasswordHistoryPolicy;
import com.example.backend.scim.domain.PasswordPolicyRefusedException;
import com.example.backend.scim.domain.PasswordReusedException;
import com.example.backend.scim.domain.PreconditionFailedException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ScimPatchRefusedException;
import com.example.backend.scim.domain.ScimRequestRefusedException;
import com.example.backend.scim.domain.ScimValueControlCharacterException;
import com.example.backend.scim.domain.ScimValueEdgeWhitespaceException;
import com.example.backend.scim.domain.ScimValueTooLongException;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The one table of SCIM refusals: for each kind, what the caller is told and what the refusal is
 * audited as.
 *
 * <p>A refusal is audited by what it is, not by which module caught it. A reader's
 * {@code mutability} and the directory's are one {@link Kind}, so both are recorded alike, and no
 * use case catches a refusal to audit it on the way out: {@link ScimExceptionHandler} records it
 * once, at the edge of the request, from three tables here.
 *
 * <ul>
 *   <li>{@link Kind} — the status, the {@code scimType}, the log reason and the audit reason of
 *       every kind. A kind no {@link AuditScimRefusal} names is answered and logged, never
 *       audited.
 *   <li>{@link #answer} — which kind, with which detail, each refusal the directory raises is.
 *       Exhaustive over the sealed {@link ScimRequestRefusedException}, so a new directory refusal
 *       is one entry here and does not compile without it.
 *   <li>{@link #audited} — which refused-write event the request's route records. A read has none:
 *       a single read is not audited at all, and a refused bulk read read nothing, so a refusal on
 *       a read route is answered and logged only.
 * </ul>
 *
 * <p>Every event recorded here is an {@link Audited.Refused}, so the append is fail-open with an
 * alert (ADR 0004): a trail that cannot record the refusal never changes its answer. By the time
 * the edge records it, the write's transaction has already rolled back, which a fail-open append —
 * committed on a connection of its own — does not need.
 */
final class ScimRefusals {

    // The controllers' own mappings, so the switch in audited() matches the template the
    // dispatcher reports; every SCIM write route is pinned to an event by
    // ScimRefusalAuditTests::every_scim_write_route_records_its_refusal.
    private static final String USERS = ScimSchemas.USERS_PATH;

    private static final String USER = USERS + ScimSchemas.ID_PATH;

    private static final String GROUPS = ScimSchemas.GROUPS_PATH;

    private static final String GROUP = GROUPS + ScimSchemas.ID_PATH;

    private ScimRefusals() {
    }

    /**
     * Every kind of refusal a SCIM caller can receive.
     *
     * <p>{@code reason} is what the refusal's log record carries as {@code event.reason}: the
     * {@code scimType} where RFC 7644 defines one, and otherwise the kind's own name.
     * {@code audited} is the audit reason, the {@code scimType} upper-cased, so a recorded refusal
     * and the error body the caller received name the same thing.
     */
    enum Kind {

        /** The body was not valid SCIM: unparseable, or missing something required. */
        INVALID_SYNTAX(HttpStatus.BAD_REQUEST, "invalidSyntax", null),

        /** A value or an attribute was not acceptable. */
        INVALID_VALUE(HttpStatus.BAD_REQUEST, "invalidValue", AuditScimRefusal.INVALID_VALUE),

        /** A live resource already holds a unique attribute the write asked for. */
        UNIQUENESS(HttpStatus.CONFLICT, "uniqueness", AuditScimRefusal.UNIQUENESS),

        /** The write targeted something that cannot be changed (ADR 0006). */
        MUTABILITY(HttpStatus.BAD_REQUEST, "mutability", AuditScimRefusal.MUTABILITY),

        /** A PATCH path that is malformed or names no attribute this service implements. */
        INVALID_PATH(HttpStatus.BAD_REQUEST, "invalidPath", null),

        /** A PATCH filter that selected no value. */
        NO_TARGET(HttpStatus.BAD_REQUEST, "noTarget", AuditScimRefusal.NO_TARGET),

        /** A filter using syntax or a comparison this service does not support. */
        INVALID_FILTER(HttpStatus.BAD_REQUEST, "invalidFilter", null),

        /**
         * An {@code If-Match} that is a wildcard, a list, repeated, or not an entity tag:
         * {@code invalidValue} on the wire, and deliberately NOT audited, as a stale one is not.
         * A refused precondition is the ordinary signal of two writers colliding, carries nothing
         * about the resource, and is counted by the operational telemetry instead.
         */
        INVALID_PRECONDITION(HttpStatus.BAD_REQUEST, "invalidValue", "invalidValue", null),

        /** A write's {@code If-Match} named a version that is no longer current. Not audited. */
        PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, null, "preconditionFailed", null),

        /** No live resource has this id, or no such discovery document exists. */
        NOT_FOUND(HttpStatus.NOT_FOUND, null, "notFound", null),

        /** A query capability this service does not implement (RFC 7644 §3.4.2.2). */
        UNSUPPORTED_QUERY(HttpStatus.FORBIDDEN, null, "unsupportedQuery", null),

        /** The request body ran past the namespace's size bound while it was being read. */
        PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, null, "payloadTooLarge", null),

        /** An endpoint RFC 7644 defines and this service deliberately does not implement. */
        NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, null, "notImplemented", null),

        /** A failure on this side that the request could not have avoided. */
        SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, null, "serverError", null),

        /** A retried write the database chose as the deadlock's victim again (ADR 0015, as-13). */
        SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, null, "serviceUnavailable", null);

        final HttpStatus status;

        final String scimType;

        final String reason;

        final AuditScimRefusal audited;

        Kind(HttpStatus status, String scimType, AuditScimRefusal audited) {
            this(status, scimType, scimType, audited);
        }

        Kind(HttpStatus status, String scimType, String reason, AuditScimRefusal audited) {
            this.status = status;
            this.scimType = scimType;
            this.reason = reason;
            this.audited = audited;
        }
    }

    /**
     * What the caller is told for a refusal the directory raised.
     *
     * <p>No detail quotes a submitted value: a {@code userName} or member id already taken or
     * unknown is not echoed — the caller sent it, and an error body is also a log line — and the
     * attribute refusals name the attribute and the rule, never the value nor where in it the
     * offending characters were.
     *
     * <p>PIT's four non-killed mutants here are javac scaffolding, not behaviour: the selector's
     * {@code requireNonNull} (only the advice calls this, with the exception the dispatcher caught,
     * which is never null) and the synthetic {@code MatchException} defaults of the three switches
     * (exhaustive over a sealed type and two enums, so no case falls through).
     * {@code ScimRefusalAuditTests::each_refusal_kind_answers_and_is_audited_by_what_it_is} drives
     * every arm and passes with them alive.
     */
    static ScimErrorException answer(ScimRequestRefusedException refused) {
        return switch (refused) {
            case DuplicateUserNameException _ -> ScimErrorException.uniqueness(
                    "A User with the requested userName already exists.");
            // RFC 7643 does not make a Group's displayName unique; this directory does, because
            // two Groups an administrator reads as the same name is how membership of the wrong
            // one gets granted. Advertised in the Group schema as uniqueness=server.
            case DuplicateDisplayNameException _ -> ScimErrorException.uniqueness(
                    "A Group with the requested displayName already exists.");
            // One detail for three causes — an id naming a Group, a deleted User, or nothing —
            // because telling them apart would disclose the existence and type of resources the
            // caller has not been shown.
            case UnknownGroupMemberException _ -> ScimErrorException.invalidValue(
                    "Every Group member must reference a live User.");
            case InvalidPreconditionException _ -> ScimErrorException.invalidPrecondition(
                    "If-Match must carry exactly one strong ETag; '*' and lists are not accepted.");
            case PreconditionFailedException _ -> ScimErrorException.preconditionFailed(
                    "The resource has changed since that ETag was issued; GET it and retry.");
            case PasswordPolicyRefusedException policy -> ScimErrorException.invalidValue(
                    "The password does not satisfy the password policy (" + policy.rule().name()
                            + "). " + policy.rule().message() + ".");
            case PasswordReusedException _ -> ScimErrorException.invalidValue(
                    "The password matches one of the User's " + PasswordHistoryPolicy.RETAINED
                            + " most recent passwords; a new value is required.");
            case ScimValueTooLongException tooLong -> ScimErrorException.invalidValue(
                    tooLong.attribute() + " must be at most " + tooLong.limit()
                            + " characters long.");
            case ScimValueControlCharacterException control ->
                    ScimErrorException.invalidValue(control.getMessage());
            case ScimValueEdgeWhitespaceException edge ->
                    ScimErrorException.invalidValue(edge.getMessage());
            case ScimPatchRefusedException patch -> switch (patch.reason()) {
                case MUTABILITY -> ScimErrorException.mutability(patch.getMessage());
                case NO_TARGET -> ScimErrorException.noTarget(patch.getMessage());
            };
            // mutability with a 400, deliberately NOT a 403: the token may hold every Permission
            // the write needs, and the refusal is about the target, not the credential (ADR 0006).
            // The detail says which kind of resource was protected, not which one, nor why.
            case ProtectedResourceException protectedResource -> ScimErrorException.mutability(
                    switch (protectedResource.reservedName()) {
                        case BOOTSTRAP_ADMIN ->
                                "This User is reserved for deployment recovery; it cannot be"
                                        + " deleted, and its attributes and its Group membership"
                                        + " cannot be changed.";
                        case ADMIN_GROUP ->
                                "This Group is reserved for deployment recovery; it cannot be"
                                        + " renamed or deleted, though its ordinary membership may"
                                        + " change.";
                    });
            // The parser's message quotes no literal from the filter.
            case InvalidScimFilterException filter ->
                    ScimErrorException.invalidFilter(filter.getMessage());
            case InvalidScimQueryException query -> ScimErrorException.invalidValue(query.getMessage());
        };
    }

    /**
     * The refused-write event this request records for {@code refusal}, or empty when it records
     * none: a kind no audit reason names, a route whose write no event covers, a request no
     * connector authenticated, or an {@code {id}} that is not one this service could have issued
     * — which names no resource to record.
     *
     * <p>Derived from the request rather than told by the handler: the route the dispatcher
     * matched and its method say which write was refused, its {@code {id}} which resource, and the
     * bearer filter's mark which connector. A Group deletion is recorded as the refused Group
     * write it has always been; there is no refused-Group-deletion event.
     */
    static Optional<Audited> audited(ScimErrorException refusal, HttpServletRequest request) {
        AuditScimRefusal reason = refusal.kind().audited;
        UUID connectorId = RequestActor.connectorOf(request);
        if (reason == null || connectorId == null) {
            return Optional.empty();
        }
        UUID id = resourceId(request);
        // The template the dispatcher matched; a request it matched to none names no write here.
        return switch (request.getMethod() + " "
                + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)) {
            case "POST " + USERS ->
                    Optional.of(new Audited.ScimUserCreateRejected(connectorId, reason));
            case "PUT " + USER, "PATCH " + USER -> about(id,
                    userId -> new Audited.ScimUserWriteRejected(connectorId, userId, reason));
            case "DELETE " + USER -> about(id,
                    userId -> new Audited.ScimUserDeleteRejected(connectorId, userId, reason));
            case "POST " + GROUPS ->
                    Optional.of(new Audited.ScimGroupCreateRejected(connectorId, reason));
            case "PUT " + GROUP, "PATCH " + GROUP, "DELETE " + GROUP -> about(id,
                    groupId -> new Audited.ScimGroupWriteRejected(connectorId, groupId, reason));
            default -> Optional.empty();
        };
    }

    /**
     * The route's {@code {id}} as a resource id, or {@code null} where the route has none or it is
     * not a UUID: a path segment that is not one names no resource and is whatever the caller
     * typed.
     */
    static UUID resourceId(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
                instanceof Map<?, ?> variables)
                || !(variables.get("id") instanceof String id)) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static Optional<Audited> about(UUID id, Function<UUID, Audited> event) {
        return Optional.ofNullable(id).map(event);
    }
}
