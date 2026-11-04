package com.example.backend.scim.domain;

import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for connector tokens. */
public interface ScimConnectorTokenRepository {

    /** Writes a new token or replaces an existing one wholly. */
    void save(ScimConnectorToken token);

    /**
     * The token with this non-secret lookup id.
     *
     * <p>The only lookup the authentication path performs, and the reason a token
     * value has two halves: the secret one never appears in a query. Returns the
     * row whatever its state — expired and revoked tokens are found and then
     * refused, because a lookup that filtered them out would make "revoked" and
     * "never existed" two different amounts of work.
     */
    Optional<ScimConnectorToken> findByLookupId(String lookupId);

    Optional<ScimConnectorToken> findById(UUID id);

    /** Every token this connector has ever held, newest issue first. */
    List<ScimConnectorToken> findByConnectorIdOrderedByIssuedAtDesc(UUID connectorId);

    /**
     * One page of the connector's active tokens — those not revoked whose expiry is after
     * {@code now} — newest issue first, equal issue times ordered by id, with how many are active
     * in all.
     *
     * <p>A rotated token inside its overlap window is active: rotation brought its expiry forward to
     * the window's end, so once the window has passed it is simply expired. Revoked and expired rows
     * are kept, for the audit trail; they are only not part of this read.
     *
     * @param page which slice to read; a {@code count} of zero reads no tokens and still counts them
     */
    ActiveConnectorTokenPage findActiveByConnectorId(
            UUID connectorId, Instant now, PageRequest page);

    /**
     * Revokes every token the connector holds that is not already revoked, and
     * reports how many that was.
     *
     * <p>One statement rather than a read-modify-write loop: this runs inside the
     * connector-deletion transaction, where the point is that no token survives it.
     * A loop would also read every token into memory to write most of them back
     * unchanged.
     */
    int revokeAllForConnector(UUID connectorId, Instant revokedAt);
}
