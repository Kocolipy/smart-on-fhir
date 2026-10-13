package com.example.backend.scim.domain;

/**
 * A live Group already holds the {@code displayName} a write asked for.
 *
 * <p>Thrown by the persistence port rather than raised by a prior existence check, for
 * the reason {@link DuplicateUserNameException} is: uniqueness is a database constraint,
 * so the only place the answer cannot be stale is the failed statement. Two writes
 * claiming the same name at the same moment both pass a read-then-write check and one of
 * them then violates the constraint; catching the violation is what turns that into one
 * success and one {@code 409} instead of one success and one {@code 500}.
 *
 * <p>RFC 7643 does not require {@code displayName} to be unique. This directory requires
 * it because a Group's membership confers authority, and two Groups an administrator
 * reads as the same name is how membership of the wrong one gets granted.
 *
 * <p>Carries no message naming the value, for the reason the User exception does not: an
 * exception message is the shortest path into a log line.
 */
public final class DuplicateDisplayNameException extends ScimRequestRefusedException {

    public DuplicateDisplayNameException(Throwable cause) {
        super("a live SCIM Group already holds this displayName", cause);
    }
}
