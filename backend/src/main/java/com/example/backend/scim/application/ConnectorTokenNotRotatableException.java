package com.example.backend.scim.application;

import com.example.backend.scim.domain.ConnectorTokenRotationRefusal;
import java.util.UUID;

/**
 * A connector token rotation refused because the token addressed can no longer be rotated: it was
 * revoked, has expired, or was rotated already — the second of two concurrent rotations of one
 * token among them.
 *
 * <p>Nothing was minted and nothing written. Carries the administrator, the token's connector, the
 * token and the reason, which the administrative edge audits — and no message naming any of them:
 * the caller addressed the token, and the edge answers {@code 409} without a body, the listing
 * saying which state the token is in.
 */
public final class ConnectorTokenNotRotatableException
        extends ConnectorAdministrationRefusedException {

    private final UUID actorId;

    private final UUID connectorId;

    private final UUID rotatedId;

    private final ConnectorTokenRotationRefusal reason;

    /**
     * @param actorId     the acting administrator's stable id, or {@code null} for a session that
     *                    names nobody — recorded as an event with no actor
     * @param connectorId the token's connector
     * @param rotatedId   the token the rotation addressed
     */
    public ConnectorTokenNotRotatableException(
            UUID actorId, UUID connectorId, UUID rotatedId, ConnectorTokenRotationRefusal reason) {
        super("The token can no longer be rotated");
        this.actorId = actorId;
        this.connectorId = connectorId;
        this.rotatedId = rotatedId;
        this.reason = reason;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID connectorId() {
        return connectorId;
    }

    public UUID rotatedId() {
        return rotatedId;
    }

    public ConnectorTokenRotationRefusal reason() {
        return reason;
    }
}
