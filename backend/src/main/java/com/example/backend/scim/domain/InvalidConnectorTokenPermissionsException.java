package com.example.backend.scim.domain;

/**
 * A token requested with no Permission, with a name that is no Permission, or with one a token
 * cannot carry ({@code audit:read}, {@code connector:*}, {@code ops:read}, {@code patient:read}).
 *
 * <p>A malformed request, answered {@code 400}, and checked before whether the caller holds what
 * it asked for: a Permission no token can carry is refused whoever asks for it, so it is not an
 * escalation attempt but a request that cannot be granted at all.
 */
public class InvalidConnectorTokenPermissionsException extends RuntimeException {

    public InvalidConnectorTokenPermissionsException(String message) {
        super(message);
    }
}
