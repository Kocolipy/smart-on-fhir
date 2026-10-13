package com.example.backend.scim.domain;

import java.util.UUID;

/**
 * Takes a connector token's row lock before an administrative write reads the token: the
 * serialization point of a rotation or a revocation.
 *
 * <p>A rotation decides from the stored token whether it may still be replaced — not revoked, not
 * expired, not replaced already — and then writes both the replacement and the shortened old
 * token. Two rotations of one token that each read it before either wrote would both find it
 * rotatable and both mint a live replacement. Locked first, the second waits for the first to
 * commit, and its read then sees the replacement the first recorded, so it is refused. A
 * revocation takes the same lock for the same reason: it writes the whole row back from what it
 * read, and a rotation committed in between would otherwise be reverted.
 *
 * <p>The sibling of {@link ScimWriteLocks}, and implemented by the same adapter, so one place
 * issues every lock statement a write takes. A token write holds no resource lock, and a SCIM
 * write no token lock, so the two orders never meet.
 *
 * <p>The call must come before the transaction's first read of the token. A token that does not
 * exist locks nothing, and its absence is the following read's to report.
 */
public interface ConnectorTokenLocks {

    /** Locks this token's row until the transaction ends. */
    void lockToken(UUID tokenId);
}
