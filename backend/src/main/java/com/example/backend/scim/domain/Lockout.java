package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A lock on a User: when it was imposed and why. The two travel together — a lock is never stored
 * without its cause, nor a cause without a lock — so they are one value rather than two
 * parameters, and a lock that is not there cannot be passed at all.
 *
 * @param at    when the lock was imposed
 * @param cause why the User is locked
 */
public record Lockout(Instant at, LockCause cause) {

    public Lockout {
        Objects.requireNonNull(at, "a lock has an instant");
        Objects.requireNonNull(cause, "a lock has a cause");
    }
}
