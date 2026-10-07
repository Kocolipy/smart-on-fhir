package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@code private_key_jwt} client assertion (D7, D14; spec section 7 step 4), as Epic's token
 * endpoint reads it: signed ES384 by the active key under the active {@code kid}, verifiable
 * against our published JWKS, identifying the client to that one endpoint for a few minutes.
 */
class EnvironmentKeyClientAssertionSignerTests {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private static final URI TOKEN_ENDPOINT = URI.create("https://fhir.example.org/oauth2/token");

    private final KeyPair activePair = EpicTestKeys.p384KeyPair();

    private final KeyPair nextPair = EpicTestKeys.p384KeyPair();

    private final EpicSigningKeys keys = new EpicSigningKeys(
            new EpicSigningKey("active-2026-04", (ECPrivateKey) activePair.getPrivate()),
            Optional.of(new EpicSigningKey("next-2026-10", (ECPrivateKey) nextPair.getPrivate())));

    private final ClientAssertionSigner signer = new EnvironmentKeyClientAssertionSigner(
            "epic-client-id", keys, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theIssuerAndTheSubjectAreBothTheClientId() throws Exception {
        JWTClaimsSet claims = claims(signer.sign(TOKEN_ENDPOINT));

        assertThat(List.of(claims.getIssuer(), claims.getSubject()))
                .containsExactly("epic-client-id", "epic-client-id");
    }

    @Test
    void theAudienceIsTheTokenEndpointAlone() throws Exception {
        assertThat(claims(signer.sign(TOKEN_ENDPOINT)).getAudience())
                .containsExactly("https://fhir.example.org/oauth2/token");
    }

    /** Epic refuses a replayed {@code jti}, so every assertion carries its own. */
    @Test
    void everyAssertionCarriesItsOwnJti() throws Exception {
        String first = claims(signer.sign(TOKEN_ENDPOINT)).getJWTID();
        String second = claims(signer.sign(TOKEN_ENDPOINT)).getJWTID();

        assertThat(List.of(first, second)).doesNotContainNull().doesNotHaveDuplicates();
    }

    /**
     * Issued now, by the injected clock, and expiring four minutes later: within Epic's five-minute
     * ceiling with a minute to spare for skew between our clock and Epic's.
     */
    @Test
    void theAssertionIsIssuedNowAndExpiresFourMinutesLater() throws Exception {
        JWTClaimsSet claims = claims(signer.sign(TOKEN_ENDPOINT));

        assertThat(List.of(claims.getIssueTime(), claims.getExpirationTime())).containsExactly(
                Date.from(Instant.parse("2026-10-06T12:00:00Z")),
                Date.from(Instant.parse("2026-10-06T12:04:00Z")));
    }

    @Test
    void theHeaderNamesEs384AndTheActiveKid() throws Exception {
        Map<String, Object> header = SignedJWT.parse(signer.sign(TOKEN_ENDPOINT))
                .getHeader().toJSONObject();

        assertThat(header).isEqualTo(
                Map.of("alg", "ES384", "typ", "JWT", "kid", "active-2026-04"));
    }

    /** What Epic does with it: look the {@code kid} up in our JWKS and verify the signature. */
    @Test
    void theAssertionVerifiesAgainstTheActiveKeyOurJwksPublishes() throws Exception {
        SignedJWT assertion = SignedJWT.parse(signer.sign(TOKEN_ENDPOINT));

        assertThat(assertion.verify(new ECDSAVerifier(published("active-2026-04")))).isTrue();
    }

    /** The next key is published ahead of a rotation and never signs (D14). */
    @Test
    void theNextKeyNeverSigns() throws Exception {
        SignedJWT assertion = SignedJWT.parse(signer.sign(TOKEN_ENDPOINT));

        assertThat(assertion.verify(new ECDSAVerifier(published("next-2026-10")))).isFalse();
    }

    /**
     * A key that cannot sign ES384 — off P-384, which startup validation already refuses — fails
     * the signing outright, and carries no cause: a library's message is not ours to vet (D22).
     */
    @Test
    void aKeyThatCannotSignEs384FailsWithNoCause() {
        ECPrivateKey p256 = (ECPrivateKey) EpicTestKeys.p256KeyPair().getPrivate();
        ClientAssertionSigner wrongCurve = new EnvironmentKeyClientAssertionSigner(
                "epic-client-id",
                new EpicSigningKeys(new EpicSigningKey("active-2026-04", p256), Optional.empty()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Throwable failure = catchThrowable(() -> wrongCurve.sign(TOKEN_ENDPOINT));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not sign the Epic client assertion")
                .hasNoCause();
    }

    /** The key our JWKS publishes under {@code kid}. */
    private ECKey published(String kid) throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> published =
                (List<Map<String, Object>>) EpicJwks.of(keys).document().get("keys");
        return published.stream()
                .filter(jwk -> kid.equals(jwk.get("kid")))
                .map(EnvironmentKeyClientAssertionSignerTests::parse)
                .findFirst().orElseThrow()
                .toECKey();
    }

    private static JWK parse(Map<String, Object> jwk) {
        try {
            return JWK.parse(jwk);
        } catch (java.text.ParseException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static JWTClaimsSet claims(String assertion) throws Exception {
        return SignedJWT.parse(assertion).getJWTClaimsSet();
    }
}
