package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Epic Login's own {@code id_token} checks: the nonce the callback's pending request sent,
 * bound for that callback alone (D27), and D17's MFA evidence once its switch is on.
 */
class EpicIdTokenChecksTests {

    private static final String SENT = "nonce-hash-sent-to-epic";

    @Test
    void theNonceSentIsAccepted() {
        assertThat(checkedDuringCallback(false, token(SENT, null)).hasErrors()).isFalse();
    }

    @Test
    void anotherNonceIsRefused() {
        assertThat(checkedDuringCallback(false, token("another-nonce", null)).hasErrors()).isTrue();
    }

    @Test
    void aNonceRefusalIsAnInvalidNonce() {
        assertThat(checkedDuringCallback(false, token("another-nonce", null)).getErrors())
                .extracting(OAuth2Error::getErrorCode).containsExactly("invalid_nonce");
    }

    @Test
    void anMfaRefusalIsAnInvalidIdToken() {
        assertThat(checkedDuringCallback(true, token(SENT, List.of("pwd"))).getErrors())
                .extracting(OAuth2Error::getErrorCode).containsExactly("invalid_id_token");
    }

    @Test
    void aNonceDifferingOnlyInLengthIsRefused() {
        assertThat(checkedDuringCallback(false, token(SENT + "x", null)).hasErrors()).isTrue();
    }

    @Test
    void noNonceIsRefused() {
        assertThat(checkedDuringCallback(false, token(null, null)).hasErrors()).isTrue();
    }

    /** Outside a callback that took a pending request there is no nonce to match. */
    @Test
    void aTokenCheckedOutsideACallbackIsRefused() {
        assertThat(new EpicIdTokenChecks(false).validate(token(SENT, null)).hasErrors()).isTrue();
    }

    /** A callback whose pending request sent no nonce matches nothing, an empty one included. */
    @Test
    void aCallbackThatSentNoNonceRefusesAnEmptyOne() {
        OAuth2TokenValidatorResult result = ScopedValue.where(EpicIdTokenChecks.PENDING_NONCE, "")
                .call(() -> new EpicIdTokenChecks(false).validate(token("", null)));

        assertThat(result.hasErrors()).isTrue();
    }

    /** With the switch off, {@code amr} is not read: no evidence is needed. */
    @Test
    void noMfaEvidenceIsNeededWithTheSwitchOff() {
        assertThat(checkedDuringCallback(false, token(SENT, List.of("pwd"))).hasErrors())
                .isFalse();
    }

    @Test
    void aSecondFactorIsEvidenceWithTheSwitchOn() {
        assertThat(checkedDuringCallback(true, token(SENT, List.of("pwd", "otp"))).hasErrors())
                .isFalse();
    }

    @Test
    void aPasswordAloneIsRefusedWithTheSwitchOn() {
        assertThat(checkedDuringCallback(true, token(SENT, List.of("pwd"))).hasErrors()).isTrue();
    }

    @Test
    void noAmrIsRefusedWithTheSwitchOn() {
        assertThat(checkedDuringCallback(true, token(SENT, null)).hasErrors()).isTrue();
    }

    /** The nonce is checked whatever the MFA evidence says. */
    @Test
    void anotherNonceIsRefusedEvenWithMfaEvidence() {
        assertThat(checkedDuringCallback(true, token("another", List.of("otp"))).hasErrors())
                .isTrue();
    }

    private static OAuth2TokenValidatorResult checkedDuringCallback(boolean mfaRequired, Jwt idToken) {
        return ScopedValue.where(EpicIdTokenChecks.PENDING_NONCE, SENT)
                .call(() -> new EpicIdTokenChecks(mfaRequired).validate(idToken));
    }

    private static Jwt token(String nonce, List<String> amr) {
        Jwt.Builder token = Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .subject("epic-subject")
                .issuedAt(Instant.parse("2026-10-08T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-08T00:05:00Z"));
        if (nonce != null) {
            token.claim("nonce", nonce);
        }
        if (amr != null) {
            token.claims(claims -> claims.putAll(Map.of("amr", amr)));
        }
        return token.build();
    }
}
