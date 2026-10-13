package com.example.backend.scim.domain;

/**
 * A live User already holds the {@code userName} a create asked for.
 *
 * <p>Thrown by the persistence port rather than raised by a prior existence check,
 * and that is the point: {@code userName} uniqueness is a database constraint, so
 * the only place the answer cannot be stale is the failed INSERT. Two connectors
 * creating the same {@code userName} at the same moment both pass a read-then-write
 * check and one of them then violates the constraint; catching the violation is
 * what turns that into one {@code 201} and one {@code 409} instead of one
 * {@code 201} and one {@code 500}.
 *
 * <p>Carries no message naming the value. The web adapter renders the conflict as a
 * SCIM {@code uniqueness} error, and the {@code userName} it names is one the caller
 * just sent — but it is also a value that must not reach a log line, and an
 * exception message is the shortest path into one.
 */
public final class DuplicateUserNameException extends ScimRequestRefusedException {

    public DuplicateUserNameException(Throwable cause) {
        super("a live SCIM User already holds this userName", cause);
    }
}
