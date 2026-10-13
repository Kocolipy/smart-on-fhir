package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One connector token, as this service stores it.
 *
 * <p>There is no field the plaintext value could be written into. That is the
 * "shown once" rule expressed as a shape: what is minted is handed to the issue or
 * rotation response and then exists nowhere, so this service cannot become a
 * secret-retrieval system even for an administrator who asks nicely.
 *
 * <p>{@code expiresAt} and {@code originalExpiresAt} are two fields rather than
 * one because rotation moves the first and must never move the second. Keeping the
 * issued expiry means "rotation never extended this token" is checkable against
 * the row — the database checks exactly that, in
 * {@code ck_scim_connector_tokens_expiry_never_extended}.
 *
 * @param id                stable identity, assigned before the row exists
 * @param connectorId       the connector this token authenticates
 * @param lookupId          the non-secret half of the presented value
 * @param digest            SHA-256 of the complete presented value
 * @param permissions       what the token authorises
 * @param issuedAt          when it was minted
 * @param expiresAt         when it stops being accepted; may be brought forward by
 *                          rotation, never pushed back
 * @param originalExpiresAt the expiry it was issued with
 * @param revokedAt         when it was revoked, or {@code null}
 * @param replacedByTokenId the token that replaced this one in a rotation, or
 *                          {@code null}
 */
public record ScimConnectorToken(
        UUID id,
        UUID connectorId,
        String lookupId,
        ConnectorTokenDigest digest,
        ConnectorTokenPermissions permissions,
        Instant issuedAt,
        Instant expiresAt,
        Instant originalExpiresAt,
        Instant revokedAt,
        UUID replacedByTokenId) {

    public ScimConnectorToken {
        if (expiresAt.isAfter(originalExpiresAt)) {
            throw new IllegalArgumentException(
                    "A token's expiry may never exceed the expiry it was issued with");
        }
    }

    /** A newly issued token, live until {@code expiresAt}. */
    public static ScimConnectorToken issue(
            UUID id,
            UUID connectorId,
            String lookupId,
            ConnectorTokenDigest digest,
            ConnectorTokenPermissions permissions,
            Instant issuedAt,
            Instant expiresAt) {
        return new ScimConnectorToken(
                id, connectorId, lookupId, digest, permissions, issuedAt, expiresAt, expiresAt,
                null, null);
    }

    /**
     * Whether this token is still accepted.
     *
     * <p>Revocation and expiry are both immediate and both produce the same
     * refusal, so a caller asking "may this token authenticate" asks one question.
     * Which of the two it was stays readable on the row for an administrator, and
     * is deliberately not reported to the connector.
     */
    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Whether this token has been revoked, however long ago. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Why this token can no longer be rotated at {@code now}, or empty when it can: a rotation
     * replaces a live credential once. A revoked token is refused first, then one already
     * rotated, then one whose expiry has passed — an expiry reached at exactly {@code now}
     * included, as {@link #isUsable} reads it.
     */
    public Optional<ConnectorTokenRotationRefusal> rotationRefusal(Instant now) {
        if (isRevoked()) {
            return Optional.of(ConnectorTokenRotationRefusal.REVOKED);
        }
        if (replacedByTokenId != null) {
            return Optional.of(ConnectorTokenRotationRefusal.ALREADY_ROTATED);
        }
        if (!now.isBefore(expiresAt)) {
            return Optional.of(ConnectorTokenRotationRefusal.EXPIRED);
        }
        return Optional.empty();
    }

    /**
     * This token, with its overlap window ending at {@code at}.
     *
     * <p>Refuses an expiry later than the current one: shortening is the only
     * direction rotation may move it, so an argument that would lengthen the
     * token's life is a bug in the caller rather than a value to clamp silently
     * here. {@code ConnectorTokenPolicy.overlapEnd} is where the clamping belongs,
     * and it is the only caller.
     */
    public ScimConnectorToken expiringAt(Instant at, UUID replacement) {
        if (at.isAfter(expiresAt)) {
            throw new IllegalArgumentException(
                    "Rotation may only bring a token's expiry forward");
        }
        return new ScimConnectorToken(
                id, connectorId, lookupId, digest, permissions, issuedAt, at, originalExpiresAt,
                revokedAt, replacement);
    }

    /**
     * This token, revoked. Idempotent: an already-revoked token keeps its original
     * revocation time.
     */
    public ScimConnectorToken revoked(Instant at) {
        return isRevoked() ? this : new ScimConnectorToken(
                id, connectorId, lookupId, digest, permissions, issuedAt, expiresAt,
                originalExpiresAt, at, replacedByTokenId);
    }
}
