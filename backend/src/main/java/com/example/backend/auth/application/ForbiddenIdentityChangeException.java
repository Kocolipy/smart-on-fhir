package com.example.backend.auth.application;

/**
 * An administrative change was refused because of who it targets: the caller's own account.
 *
 * <p>Rendered as {@code 403}, an instance-level policy refusal, unlike
 * {@link NoLockoutInForceException}'s {@code 409}: the request is well formed and the caller
 * holds the Permission, but this caller may not do this to this account in any state it could be
 * in.
 */
public class ForbiddenIdentityChangeException extends RuntimeException {

    public ForbiddenIdentityChangeException(String message) {
        super(message);
    }
}
