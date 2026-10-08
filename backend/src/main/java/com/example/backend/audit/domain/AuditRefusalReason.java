package com.example.backend.audit.domain;

/**
 * Why a login was refused, as this service's own vocabulary rather than as the
 * authentication library's exception message.
 *
 * <p>An enum because the alternative — the refusal's message, or even its class
 * name as a string — is a value the audit trail would be carrying without being
 * able to say where it came from. A message can name the submitted username, and
 * a class name is free to change meaning between library versions. A closed set
 * is also what lets a reader count wrong passwords against attempts on names that
 * do not exist, which is the distinction a brute-force investigation turns on.
 *
 * <p>Note what this does <em>not</em> distinguish for the caller: the response to
 * every one of these is the same bare {@code 401}. The distinction lives in the
 * audit trail, which is read by an administrator, not returned to whoever
 * submitted the credentials.
 */
public enum AuditRefusalReason {

    /** The account exists and the submitted password did not match its hash. */
    BAD_CREDENTIALS,

    /** No account carries the submitted username. */
    UNKNOWN_ACCOUNT,

    /** A lockout was in force, so the password was never compared. */
    ACCOUNT_LOCKED,

    /** An administrator had disabled the account. */
    ACCOUNT_DISABLED,

    /**
     * An Epic Login that could not complete because Epic could not be reached: a timeout or a
     * {@code 5xx} from discovery, the JWKS or the token endpoint. No account is named.
     */
    EPIC_UNAVAILABLE,

    /** An Epic launch whose {@code launch} was missing or outside D18's bounds. */
    INVALID_LAUNCH,

    /** An Epic launch whose {@code iss} was missing or not exactly the configured FHIR base. */
    ISS_MISMATCH,

    /** An Epic callback with no pending authorization request, or a {@code state} that differs. */
    INVALID_STATE,

    /** An Epic callback whose {@code code} was missing or outside D18's bounds. */
    INVALID_CODE,

    /** Epic answered with an OAuth {@code error}, or an unusable discovery document. */
    IDP_ERROR,

    /** Epic's token endpoint refused the code exchange. */
    TOKEN_EXCHANGE_FAILED,

    /** An Epic {@code id_token} whose signature could not be verified. */
    INVALID_SIGNATURE,

    /** An Epic {@code id_token} claim that failed, the MFA evidence D17 requires included. */
    INVALID_CLAIMS,

    /** An Epic {@code id_token} whose {@code fhirUser} names no Practitioner here. */
    INVALID_FHIR_USER,

    /** A refusal this service does not have its own name for yet. */
    OTHER
}
