package com.example.backend.scim.domain;

/**
 * A SCIM request the directory refuses, as a closed set of kinds.
 *
 * <p>Each subtype is one kind of refusal a connector is told about. What it is told — the status,
 * the {@code scimType}, the detail — and what the refusal is audited as are decided in one place
 * at the edge of the request, the web adapter's refusal table, which switches over this hierarchy.
 * Sealed so that table is exhaustive by construction: a new kind of refusal does not compile until
 * the table has its entry, and no use case catches a refusal to audit it on the way out.
 */
public abstract sealed class ScimRequestRefusedException extends RuntimeException
        permits DuplicateDisplayNameException, DuplicateUserNameException,
                InvalidPreconditionException, InvalidScimFilterException, InvalidScimQueryException,
                PasswordPolicyRefusedException, PasswordReusedException, PreconditionFailedException,
                ProtectedResourceException, ScimAttributeValueException, ScimPatchRefusedException,
                UnknownGroupMemberException {

    protected ScimRequestRefusedException(String message) {
        super(message);
    }

    protected ScimRequestRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
