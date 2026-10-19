package com.example.backend.auth.application;

/**
 * The session names no live SCIM User: it carries no stable id at all, or the id it carries names
 * a User that no longer exists.
 *
 * <p>Rendered as the {@code 401} an unauthenticated request receives, generic body and all, because that is what
 * such a session is — it identifies nobody, so there is nobody whose record could be returned.
 */
public class UnknownSessionIdentityException extends RuntimeException {

    public UnknownSessionIdentityException() {
        super("the session names no live SCIM User");
    }
}
