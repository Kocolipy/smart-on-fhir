package com.example.backend.scim.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What an Admin may know about a connector token.
 *
 * <p>There is no field a digest or a plaintext value could be written into, which
 * is how "the service never becomes a secret-retrieval system" is enforced rather
 * than promised: the type an Admin reads a token through cannot carry one, so no
 * reviewer has to notice that a listing endpoint was returning the wrong thing.
 * {@link IssuedConnectorToken} is the only type that carries plaintext, and it is
 * returned only by issue and rotation.
 *
 * <p>Nor is there a revocation time: only an active token is listed, and an active token is
 * never revoked. A revoked token's row is kept, and its revocation is in the audit log.
 *
 * <p>{@code active} is this service's own evaluation at the moment it answers, not
 * a comparison the client makes against its own clock — the same decision the
 * account listing made about whether a lockout is in force.
 *
 * @param id                the token's stable id, which is what a revoke names
 * @param permissions       what it authorises, sorted by name
 * @param issuedAt          when it was minted
 * @param expiresAt         when it stops being accepted, as it currently stands
 * @param originalExpiresAt the expiry it was issued with, so a shortened overlap
 *                          window is visible as one
 * @param active            whether it would be accepted right now
 * @param rotated           whether a rotation has replaced it: a listed rotated token is inside
 *                          its overlap window, still accepted until {@code expiresAt}, and cannot
 *                          be rotated again
 */
public record ConnectorTokenSummary(
        UUID id,
        List<String> permissions,
        Instant issuedAt,
        Instant expiresAt,
        Instant originalExpiresAt,
        boolean active,
        boolean rotated) {
}
