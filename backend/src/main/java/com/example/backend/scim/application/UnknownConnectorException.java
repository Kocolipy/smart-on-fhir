package com.example.backend.scim.application;

import java.util.UUID;

/**
 * A connector or token id that names nothing, or names something already deleted — a token whose
 * connector was deleted among them.
 *
 * <p>One exception for both, because the caller is an authenticated administrator for whom the
 * distinction between "never existed" and "gone" carries no privilege — they can read either from
 * the listing. A connector has no such exception on the SCIM side: a request whose token names a
 * deleted connector is refused as an invalid token, which tells the connector nothing about why.
 *
 * <p>Carries the operation refused, the administrator and what it addressed, which the
 * administrative edge audits as {@code UNKNOWN_RESOURCE}; nothing was written.
 */
public final class UnknownConnectorException extends ConnectorAdministrationRefusedException {

    /** The operation that addressed nothing. */
    public enum Operation {
        DELETE,
        TOKEN_ISSUE,
        TOKEN_ROTATE,
        TOKEN_REVOKE
    }

    private final Operation operation;

    private final UUID actorId;

    private final UUID connectorId;

    private final UUID addressedId;

    /**
     * @param actorId     the acting administrator's stable id, or {@code null} for a session that
     *                    names nobody — recorded as an event with no actor
     * @param connectorId the connector addressed, or {@code null} when only an unknown token was
     * @param addressedId the token the operation addressed — rotated or revoked, never one newly
     *                    issued — or {@code null} for an operation on a connector
     */
    public UnknownConnectorException(
            Operation operation, UUID actorId, UUID connectorId, UUID addressedId) {
        super("No live connector or token with that id");
        this.operation = operation;
        this.actorId = actorId;
        this.connectorId = connectorId;
        this.addressedId = addressedId;
    }

    public Operation operation() {
        return operation;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID connectorId() {
        return connectorId;
    }

    public UUID addressedId() {
        return addressedId;
    }
}
