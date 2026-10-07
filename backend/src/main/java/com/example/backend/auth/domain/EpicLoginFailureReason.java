package com.example.backend.auth.domain;

/**
 * Why an Epic Login was refused (spec section 5, "Audit"): the closed list an Epic
 * {@code LOGIN_FAILURE} carries exactly one of, and the {@code reason} tag of the
 * {@code epic.login} counter, both spelled as the constant's name.
 *
 * <p>The reasons are audit-only. The browser is told nothing but {@code /?signin=refused}
 * (D23), and the operational log says only "Epic sign-in refused" (spec section 11): the
 * account reasons in particular tell whether an account exists, which an investigation needs
 * and an operational log reader does not.
 *
 * <p>Today the list holds the login decision's three account reasons (flow step 6). The
 * protocol reasons — the launch, {@code state}, the code, the token exchange, the
 * {@code id_token} and {@code fhirUser} — and Epic being unavailable join it with the steps that
 * refuse for them.
 */
public enum EpicLoginFailureReason {

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
