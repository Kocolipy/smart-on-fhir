package com.example.backend.scim.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Locked reads for the connector lifecycle: the live connector, locked, and one of its tokens,
 * locked. Each answer is read after its row lock is granted, so no lifecycle write can decide
 * anything from a row it does not hold — there is no way to read first and lock later.
 *
 * <p>The database runs read-committed snapshot (ADR 0013), so an unlocked read sees the last
 * committed version and waits for nobody. Two deletes of one connector that each read it first
 * would both find it live, both revoke its tokens and both record the delete; a token issued
 * while the connector is being deleted would commit a live token for a deleted connector. With
 * the connector's row locked before it is read, the second delete waits for the first, then reads
 * it deleted and is refused; an issue waits for a delete, then reads the connector deleted and is
 * refused, or the delete waits for the issue, then reads — and revokes — the token it committed.
 *
 * <p>A rotation decides from the stored token whether it may still be replaced — not revoked, not
 * expired, not replaced already — and a revocation writes the whole row back from what it read,
 * so each holds the token's row, read under its lock, as well.
 *
 * <p>The order is the connector's row before its tokens' rows, and the shape of this interface is
 * what keeps it: a token is locked only as one of a connector already locked here. A delete
 * writes every token row of its connector after locking the connector, so a rotation or a
 * revocation that took the token first and the connector second could each hold a row the other
 * waits for.
 *
 * <p>The sibling of {@link ScimWriteLocks}, and implemented by the same adapter, so one place
 * issues every lock statement the scim slice takes. A connector lifecycle write holds no resource
 * lock, and a SCIM write no connector or token lock, so the two orders never meet.
 *
 * <p>Each lock is the calling transaction's, so a call outside one is refused, as every lock of
 * {@link ScimWriteLocks} is: on an auto-commit connection the row lock would end with its
 * statement and exclude no concurrent writer.
 */
public interface ConnectorLifecycleLocks {

    /**
     * Locks the connector's row until the transaction ends, then reads it: the connector when it
     * is live, empty when it is unknown or deleted. An unknown id locks nothing.
     */
    Optional<ScimConnector> lockLiveConnector(UUID connectorId);

    /**
     * Locks the token's row until the transaction ends, then reads it: the token when it is one of
     * {@code connector}'s, empty when it is unknown or another connector's.
     *
     * @param connector the live connector, as {@link #lockLiveConnector} answered it in this
     *                  transaction: its row is locked before any of its tokens' rows
     */
    Optional<ScimConnectorToken> lockTokenOf(ScimConnector connector, UUID tokenId);
}
