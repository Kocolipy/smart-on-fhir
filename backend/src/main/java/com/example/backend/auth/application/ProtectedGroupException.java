package com.example.backend.auth.application;

/**
 * A change of the Admin group's Permissions, refused: its direct members' power is the Superuser,
 * which is not a Permission, so there is nothing a Group's Permissions could say about it
 * (ADR 0015). The web adapter renders it as a {@code 409} — the caller is the Superuser and the
 * request is well formed; the Group's nature is what refuses it.
 */
public class ProtectedGroupException extends RuntimeException {

    public ProtectedGroupException() {
        super("the Admin group carries no Permissions");
    }
}
