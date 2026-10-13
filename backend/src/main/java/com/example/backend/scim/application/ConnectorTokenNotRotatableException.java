package com.example.backend.scim.application;

/**
 * A connector token rotation refused because the token addressed can no longer be rotated: it was
 * revoked, has expired, or was rotated already — the second of two concurrent rotations of one
 * token among them.
 *
 * <p>Answered {@code 409}, bodiless: the request is well-formed and the caller may rotate tokens,
 * but this token's state forbids it, and the listing says which state that is. Nothing was minted
 * and nothing written. Carries no message naming the token — the caller addressed it, and the audit
 * trail records it with the reason.
 */
public class ConnectorTokenNotRotatableException extends RuntimeException {

    public ConnectorTokenNotRotatableException() {
        super("The token can no longer be rotated");
    }
}
