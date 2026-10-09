package com.example.backend.auth.domain;

/**
 * Why an Epic Login was refused (ADR 0013, "Audit"): the closed list an Epic
 * {@code LOGIN_FAILURE} carries exactly one of, and the {@code reason} tag of the
 * {@code epic.login} counter, both spelled as the constant's name.
 *
 * <p>The reasons are audit-only. The browser is told nothing but {@code /?signin=refused}
 * (D23), and the operational log says only "Epic sign-in refused" (ADR 0013, "the account
 * reasons are audit-only"): the account reasons in particular tell whether an account exists,
 * which an investigation needs and an operational log reader does not.
 *
 * <p>The three account reasons are decided by the login decision (flow step 6); every other one
 * by the Epic failure handler (flow step 8). Each is recorded once, by the one module that records
 * how an Epic Login ended.
 */
public enum EpicLoginFailureReason {

    /** {@code launch} missing or outside D18's bounds, or none pending at the authorize hop. */
    INVALID_LAUNCH,

    /** {@code iss} missing, or not exactly {@code APP_EPIC_FHIR_BASE} (D10). */
    ISS_MISMATCH,

    /** No pending authorization request at the callback, or a {@code state} that differs (D27). */
    INVALID_STATE,

    /** {@code code} missing, or outside D18's bounds. */
    INVALID_CODE,

    /** Epic answered the callback with an OAuth {@code error}, or an unusable discovery document. */
    IDP_ERROR,

    /** The token endpoint refused the exchange ({@code invalid_grant}, {@code invalid_client}, …). */
    TOKEN_EXCHANGE_FAILED,

    /**
     * Epic could not be reached: a connect or read timeout, or a {@code 5xx}, from discovery, the
     * JWKS or the token endpoint (D23). Not a refusal: the browser is told to try again shortly.
     */
    EPIC_UNAVAILABLE,

    /**
     * The {@code id_token}'s signature could not be verified: a bad signature, an algorithm other
     * than RS256, a {@code kid} still unknown after D26's refetches, or an unusable JWKS.
     */
    INVALID_SIGNATURE,

    /**
     * An {@code id_token} claim failed: {@code iss}, {@code aud}, {@code azp}, {@code exp},
     * {@code iat} or {@code nonce}, or the MFA evidence D17 requires once its switch is on.
     */
    INVALID_CLAIMS,

    /** {@code fhirUser} absent, or not {@code {fhirBase}/Practitioner/{id}}. */
    INVALID_FHIR_USER,

    /**
     * No User's stored {@code userName} equals the Practitioner ID exactly — none at all, or
     * only a case variant (D3) — or the one that does is the Bootstrap Admin (D6).
     */
    UNKNOWN_ACCOUNT,

    /** The linked User is deactivated. */
    ACCOUNT_DISABLED,

    /** The linked User is locked, for whatever cause: a failure run or dormancy (D12). */
    ACCOUNT_LOCKED
}
