package com.example.backend.scim.domain;

/**
 * Why a connector token can no longer be rotated, as a closed set, in the order they are read:
 * a token that is several of these at once is refused for the first.
 */
public enum ConnectorTokenRotationRefusal {

    /** The token has been revoked. Revocation is final, so it is read first. */
    REVOKED,

    /**
     * The token has been rotated already, and a replacement exists. A second rotation would mint a
     * second live replacement for one credential — the loser of two concurrent rotations is
     * refused here.
     */
    ALREADY_ROTATED,

    /** The token's expiry has passed: there is no live credential left to replace. */
    EXPIRED
}
