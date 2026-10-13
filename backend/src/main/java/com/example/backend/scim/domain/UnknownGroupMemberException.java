package com.example.backend.scim.domain;

/**
 * A Group write named a member that is not a live User.
 *
 * <p>One exception for three cases a caller cannot tell apart and must not be able to: a
 * member id that names a Group, one that names a User that has been deleted, and one that
 * names nothing at all. They are one refusal because the schema makes them one — the
 * membership row's foreign key points at the User table, so all three are the same
 * violation — and because distinguishing them would disclose the existence and type of
 * resources the caller has not been shown.
 *
 * <p>Thrown by the persistence port from the failed statement rather than raised by a
 * prior existence check, for the reason {@link DuplicateUserNameException} is: a User
 * deleted between the check and the write would otherwise be accepted as a member.
 *
 * <p>Carries no message naming the id. The id is one the caller just sent, so the
 * rendered error may name it; the exception is what a log line is built from, and a
 * pattern of probed ids is not worth retaining.
 */
public final class UnknownGroupMemberException extends ScimRequestRefusedException {

    public UnknownGroupMemberException(Throwable cause) {
        super("a Group member must be a live SCIM User", cause);
    }
}
