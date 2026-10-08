package com.example.backend.auth.epic.config;

import com.example.backend.auth.domain.EpicMfaEvidence;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The {@code id_token} checks Epic Login adds to the OpenID Connect ones (flow step 4), run by
 * the decoder once the signature has verified, each failure a claim failure
 * ({@code INVALID_CLAIMS}).
 *
 * <ul>
 *   <li><b>The nonce</b> must be the one the pending authorization request sent, compared in
 *       constant time (D27). The callback that took the pending request binds its nonce to
 *       {@link #PENDING_NONCE} for the rest of that request alone, so it is never stored again
 *       and is gone when the request ends; a decode outside such a callback has no nonce to
 *       match, and fails.
 *   <li><b>The MFA factor</b> (D17): the token must have one, as {@link EpicMfaEvidence#factorOf}
 *       decides — with the switch on, {@code amr} must name a second factor or {@code mfa}; with
 *       it off the organisation's MFA is attested, and {@code amr} is not read.
 * </ul>
 */
final class EpicIdTokenChecks implements OAuth2TokenValidator<Jwt> {

    /** The nonce the callback's pending request sent Epic, for that callback only. */
    static final ScopedValue<String> PENDING_NONCE = ScopedValue.newInstance();

    /** The OpenID Connect claim the nonce comes back in. */
    static final String NONCE = "nonce";

    private final boolean mfaEvidenceRequired;

    EpicIdTokenChecks(boolean mfaEvidenceRequired) {
        this.mfaEvidenceRequired = mfaEvidenceRequired;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt idToken) {
        String expected = PENDING_NONCE.isBound() ? PENDING_NONCE.get() : "";
        String received = idToken.getClaimAsString(NONCE);
        if (expected.isEmpty() || !ConstantTime.equals(received, expected)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_nonce", "The nonce is not the one sent", null));
        }
        if (EpicMfaEvidence.factorOf(mfaEvidenceRequired,
                () -> idToken.getClaimAsStringList(EpicMfaEvidence.AMR)).isEmpty()) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_id_token", "No MFA evidence in amr", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
