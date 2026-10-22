package com.example.backend.auth.application;

/**
 * An Unlock was refused because the User it addressed is serving no lockout: there is nothing for
 * it to lift, and an Unlock has no effect on an unlocked User (ADR 0015's 2026-10-22 addendum).
 *
 * <p>Not an authorization failure: the caller holds {@code account:unlock} and may unlock this
 * User, but the User's state does not admit the action — most often because somebody else
 * unlocked it after the caller's listing was read. That is why the web adapter renders it as a
 * {@code 409}, unlike {@link ForbiddenIdentityChangeException}'s {@code 403}, which no state of the
 * target could lift.
 */
public class NoLockoutInForceException extends RuntimeException {

    public NoLockoutInForceException(String message) {
        super(message);
    }
}
