package com.example.backend.auth.application;

/**
 * An administrative change was refused because of what it would leave behind.
 *
 * <p>Not an authorization failure: the caller is an authenticated administrator and the action is
 * what is refused. That is why the web adapter renders it as a {@code 409} — neither {@code 403}
 * (the role is fine) nor {@code 400} (the request is well formed) describes it.
 *
 * <p>The one refusal it represents today is a forced password change on a User with no password,
 * which has no credential to replace. It once also covered refusals that would have left the
 * deployment with nobody able to reverse a change — an identity disabling itself, or the last
 * enabled administrator being disabled — and those are gone: there is no Disable action, and the
 * deployment's recovery is guaranteed structurally instead, by the Bootstrap Admin's frozen
 * membership of the Admin group, whose direct members are the Superuser (ADR 0015).
 */
public class UnsafeIdentityChangeException extends RuntimeException {

    public UnsafeIdentityChangeException(String message) {
        super(message);
    }
}
