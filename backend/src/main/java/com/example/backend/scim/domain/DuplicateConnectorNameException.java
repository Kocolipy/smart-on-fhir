package com.example.backend.scim.domain;

/**
 * A live connector already holds the display name a create asked for.
 *
 * <p>Thrown by the persistence port rather than raised by a prior existence check, for the reason
 * {@link DuplicateDisplayNameException} is: uniqueness is a database index, so the only place the
 * answer cannot be stale is the failed statement. Two creates claiming one name at the same moment
 * both pass a read-then-write check, and one of them then violates the index; catching the
 * violation is what turns that into one {@code 201} and one {@code 409} rather than a
 * {@code 500}.
 *
 * <p>Carries no message naming the value: an exception message is the shortest path into a log
 * line.
 */
public final class DuplicateConnectorNameException extends RuntimeException {

    public DuplicateConnectorNameException(Throwable cause) {
        super("a live connector already holds this display name", cause);
    }
}
