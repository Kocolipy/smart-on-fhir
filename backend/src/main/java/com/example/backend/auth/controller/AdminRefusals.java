package com.example.backend.auth.controller;

import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.application.AdministrativeRefusalException;
import com.example.backend.auth.application.GroupPermissionsRefusedException;
import com.example.backend.auth.application.UnlockRefusedException;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.application.ConnectorAdministrationRefusedException;
import com.example.backend.scim.application.ConnectorTokenNotRotatableException;
import com.example.backend.scim.application.UnknownConnectorException;
import com.example.backend.scim.domain.ConnectorTokenRotationRefusal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpStatus;

/**
 * The one table of administrative refusals: for each refusal an administrator can meet under
 * {@code /api/admin}, the status and body it is answered with, the {@code WARN} record it writes
 * of its own, and the audit row it appends.
 *
 * <p>A refusal is recorded by what it is, not by which use case raised it, and no use case records
 * one: it throws a typed refusal naming who, what and why, and {@link AdminExceptionHandler}
 * records it here, once, at the edge of the request. The table is exhaustive over the two sealed
 * refusal types — {@link AdministrativeRefusalException} for identities and Groups,
 * {@link ConnectorAdministrationRefusedException} for connectors and their tokens — and over each
 * one's reasons, so a new refusal does not compile without its entry. It is in {@code auth}
 * because {@code auth} reaches {@code scim} and never the reverse.
 *
 * <p>Every row is an {@link Audited.Refused}, so the append is fail-open with an alert (ADR 0004):
 * a trail that cannot record the refusal never changes its answer. By the time the edge records
 * it, the use case's transaction has already rolled back, which a fail-open append — committed on
 * a connection of its own — does not need. Every refusal addressing something is audited, an
 * unknown target included; the only refusals that are not are a token request of a shape no token
 * may have ({@link #ofMalformedTokenRequest()}).
 *
 * <p>The log record and the response are exactly what each use case and controller produced
 * before the table existed: an unknown User, connector or token was answered without a record of
 * its own, and still is — the request record carries its status — while an unknown Group was, and
 * is, recorded like the Group's other refusals.
 */
final class AdminRefusals {

    private static final Logger log = LoggerFactory.getLogger(AdminRefusals.class);

    private AdminRefusals() {
    }

    /** What the caller receives beside the status. */
    enum Body {

        /** The application API's one error body, {@code ApiError}. */
        API_ERROR,

        /** No body at all; the listing says what the refusal was about. */
        NONE
    }

    /**
     * One row of the table.
     *
     * @param logged  the refusal's own {@code WARN} record, built and not yet written — the
     *                operation, its classification and the fields it names, never a value the
     *                caller submitted — or {@code null} when it writes none. Built here, beside the
     *                operation it names, so {@code OperationMessagesTests} reads that the operation
     *                needs its refusal message.
     * @param audited the refused event it appends, or {@code null} when it is not audited — typed
     *                {@link Audited.Refused}, so no row can be appended fail-closed
     */
    record Entry(HttpStatus status, Body body, LoggingEventBuilder logged,
            Audited.Refused audited) {
    }

    // ---- identities and Groups ---------------------------------------------------------------

    static Entry of(AdministrativeRefusalException refused) {
        return switch (refused) {
            case UnlockRefusedException unlock -> unlock(unlock);
            case GroupPermissionsRefusedException change -> groupPermissions(change);
        };
    }

    /**
     * An Unlock: a refused {@code LOCKOUT_LIFT}, and a record naming the User by id. A refusal
     * about whose account it is — the caller's own — is a bodiless {@code 403}; one about the
     * User's state the generic {@code 409} document; an unknown id a bodiless {@code 404}.
     */
    private static Entry unlock(UnlockRefusedException refused) {
        return switch (refused.reason()) {
            // No record of its own, as before the table: the request record carries the 404.
            case UNKNOWN_IDENTITY -> new Entry(HttpStatus.NOT_FOUND, Body.NONE, null,
                    unlockRefused(refused, AuditAdministrativeRefusal.UNKNOWN_RESOURCE));
            case SELF_TARGET -> new Entry(HttpStatus.FORBIDDEN, Body.NONE,
                    unlockRecord(refused, AuditAdministrativeRefusal.SELF_TARGET),
                    unlockRefused(refused, AuditAdministrativeRefusal.SELF_TARGET));
            case NOT_LOCKED -> new Entry(HttpStatus.CONFLICT, Body.API_ERROR,
                    unlockRecord(refused, AuditAdministrativeRefusal.NOT_LOCKED),
                    unlockRefused(refused, AuditAdministrativeRefusal.NOT_LOCKED));
        };
    }

    /**
     * A change of a Group's Permissions: a refused {@code GROUP_PERMISSIONS_CHANGE} with one
     * generic reason, never a Permission, and the {@code ApiError} document under its status. The
     * caller is the Superuser, so a {@code 404} naming what is missing reveals nothing the listing
     * would not.
     */
    private static Entry groupPermissions(GroupPermissionsRefusedException refused) {
        return switch (refused.reason()) {
            case INVALID_PERMISSIONS -> groupPermissions(refused, HttpStatus.BAD_REQUEST,
                    AuditAdministrativeRefusal.INVALID_PERMISSIONS);
            case UNKNOWN_GROUP -> groupPermissions(refused, HttpStatus.NOT_FOUND,
                    AuditAdministrativeRefusal.UNKNOWN_RESOURCE);
            case ADMIN_GROUP -> groupPermissions(refused, HttpStatus.CONFLICT,
                    AuditAdministrativeRefusal.PROTECTED_RESOURCE);
        };
    }

    // ---- connectors and their tokens ---------------------------------------------------------

    static Entry of(ConnectorAdministrationRefusedException refused) {
        return switch (refused) {
            case UnknownConnectorException unknown -> unknownConnector(unknown);
            case ConnectorTokenNotRotatableException notRotatable -> notRotatable(notRotatable);
        };
    }

    /**
     * An operation whose connector or token names nothing: a bodiless {@code 404}, no record of
     * its own as before the table, and the operation's refused event with {@code UNKNOWN_RESOURCE},
     * naming what was addressed.
     */
    private static Entry unknownConnector(UnknownConnectorException refused) {
        AuditAdministrativeRefusal reason = AuditAdministrativeRefusal.UNKNOWN_RESOURCE;
        return new Entry(HttpStatus.NOT_FOUND, Body.NONE, null, switch (refused.operation()) {
            case DELETE -> new Audited.ConnectorDeleteRefused(
                    refused.actorId(), refused.connectorId(), reason);
            case TOKEN_ISSUE -> new Audited.ConnectorTokenIssueRefused(
                    refused.actorId(), refused.connectorId(), reason);
            case TOKEN_ROTATE -> new Audited.ConnectorTokenNotRotatable(
                    refused.actorId(), refused.connectorId(), refused.addressedId(), reason);
            case TOKEN_REVOKE -> new Audited.ConnectorTokenRevokeRefused(
                    refused.actorId(), refused.connectorId(), refused.addressedId(), reason);
        });
    }

    /**
     * A rotation of a token that can no longer be rotated: a bodiless {@code 409} — the listing
     * shows the token's state — a record naming neither the token nor why, and the refused
     * rotation naming both.
     */
    private static Entry notRotatable(ConnectorTokenNotRotatableException refused) {
        return new Entry(HttpStatus.CONFLICT, Body.NONE,
                LogEvent.refused(log, Operation.CONNECTOR_TOKEN_ROTATE, Category.CONFIGURATION,
                        Type.ADMIN, Type.DENIED),
                new Audited.ConnectorTokenNotRotatable(refused.actorId(), refused.connectorId(),
                        refused.rotatedId(), auditReason(refused.reason())));
    }

    /**
     * A token request of a shape no token may have — a lifetime past the ceiling, or Permissions no
     * token may carry — refused before any connector is looked up: a bodiless {@code 400}, and
     * nothing else. Not audited, and no record of its own: the request names nothing that exists to
     * record a refusal against, and it is the caller's malformed request, as every other
     * {@code 400} of the application API is.
     */
    static Entry ofMalformedTokenRequest() {
        return new Entry(HttpStatus.BAD_REQUEST, Body.NONE, null, null);
    }

    // ---- rows --------------------------------------------------------------------------------

    private static Audited.Refused unlockRefused(
            UnlockRefusedException refused, AuditAdministrativeRefusal reason) {
        return new Audited.UnlockRefused(refused.actorId(), refused.userId(), reason);
    }

    /**
     * An Unlock's record: the User acted on as {@code user.target.id}, never by {@code userName};
     * the administrator is {@code user.id}, which the request's logging context already carries.
     */
    private static LoggingEventBuilder unlockRecord(
            UnlockRefusedException refused, AuditAdministrativeRefusal reason) {
        return about(LogEvent.refused(log, Operation.UNLOCK, Category.PROCESS,
                        Type.ADMIN, Type.USER, Type.DENIED),
                LogEvent.USER_TARGET_ID, refused.userId(), reason);
    }

    private static Entry groupPermissions(GroupPermissionsRefusedException refused,
            HttpStatus status, AuditAdministrativeRefusal reason) {
        return new Entry(status, Body.API_ERROR,
                about(LogEvent.refused(log, Operation.GROUP_PERMISSIONS_CHANGE, Category.PROCESS,
                                Type.ADMIN, Type.GROUP, Type.DENIED),
                        LogEvent.GROUP_ID, refused.groupId(), reason),
                new Audited.GroupPermissionsChangeRefused(
                        refused.actorId(), refused.groupId(), reason));
    }

    /** The audit trail's spelling of why a token can no longer be rotated. */
    private static AuditAdministrativeRefusal auditReason(ConnectorTokenRotationRefusal refusal) {
        return switch (refusal) {
            case REVOKED -> AuditAdministrativeRefusal.TOKEN_REVOKED;
            case ALREADY_ROTATED -> AuditAdministrativeRefusal.TOKEN_ALREADY_ROTATED;
            case EXPIRED -> AuditAdministrativeRefusal.TOKEN_EXPIRED;
        };
    }

    /** {@code record} naming the subject by id under {@code key}, then the reason. */
    private static LoggingEventBuilder about(LoggingEventBuilder record,
            String key, UUID subjectId, AuditAdministrativeRefusal reason) {
        return record
                .addKeyValue(key, subjectId.toString())
                .addKeyValue(LogEvent.REASON, reason.name());
    }
}
