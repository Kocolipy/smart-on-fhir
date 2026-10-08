package com.example.backend.audit.domain;

/**
 * The multi-factor authentication an Epic {@code LOGIN_SUCCESS} was made with (ADR 0013,
 * D17): a closed set, as every classification crossing the {@link AuditTrail} is, so no value
 * Epic sent can reach the trail as text.
 *
 * <p>While Epic has not confirmed that an EHR launch's {@code id_token} carries {@code amr}, the
 * Epic organisation's MFA is an attestation, recorded as {@link #IDP_ATTESTED}. Once it has, and
 * the switch requiring the evidence is on, the factor is the one {@code amr} named: an RFC 8176
 * Authentication Method Reference value that is a possession or inherence factor, or
 * {@link #MFA} when {@code amr} said only {@code mfa}. A password ({@code pwd}) or a PIN is never
 * a second factor, and so never the one recorded.
 */
public enum AuditMfaFactor {

    /** The Epic organisation attests that its sign-in enforces MFA (ADR 0013). */
    IDP_ATTESTED("idp-attested"),

    /** {@code amr} said MFA was used, naming no factor. */
    MFA("mfa"),

    /** A one-time password. */
    OTP("otp"),

    /** A hardware-secured key. */
    HWK("hwk"),

    /** A software-secured key. */
    SWK("swk"),

    /** A code sent by SMS. */
    SMS("sms"),

    /** A telephone call. */
    TEL("tel"),

    /** A smart card. */
    SC("sc"),

    /** A fingerprint. */
    FPT("fpt"),

    /** Facial recognition. */
    FACE("face"),

    /** An iris scan. */
    IRIS("iris"),

    /** A retina scan. */
    RETINA("retina"),

    /** Voice biometrics. */
    VBM("vbm");

    private final String value;

    AuditMfaFactor(String value) {
        this.value = value;
    }

    /** The factor as it is recorded and reported, e.g. {@code idp-attested} or {@code otp}. */
    public String value() {
        return value;
    }
}
