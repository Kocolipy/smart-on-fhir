package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Our public JWKS (D14): the active key, and the next key when one is configured, each published
 * as the public half of its EC P-384 key under its {@code kid} — and never a private parameter.
 */
class EpicJwksTests {

    private final KeyPair activePair = EpicTestKeys.p384KeyPair();

    private final KeyPair nextPair = EpicTestKeys.p384KeyPair();

    @Test
    void withNoNextKeyTheJwksListsTheActiveKeyAlone() {
        EpicJwks jwks = EpicJwks.of(new EpicSigningKeys(key("active-2026-04", activePair),
                Optional.empty()));

        assertThat(kids(jwks)).containsExactly("active-2026-04");
    }

    @Test
    void withANextKeyTheJwksListsTheActiveKeyAndThenTheNextKey() {
        EpicJwks jwks = EpicJwks.of(new EpicSigningKeys(key("active-2026-04", activePair),
                Optional.of(key("next-2026-10", nextPair))));

        assertThat(kids(jwks)).containsExactly("active-2026-04", "next-2026-10");
    }

    /**
     * Each key is published as RFC 7518 describes an EC P-384 public key for ES384 signatures:
     * the coordinates are those of the public key the private key belongs to, each the 48-byte
     * big-endian value, base64url-encoded without padding.
     */
    @Test
    void eachKeyIsPublishedAsTheEcP384PublicKeyItsPrivateKeyBelongsTo() {
        EpicJwks jwks = EpicJwks.of(new EpicSigningKeys(key("active-2026-04", activePair),
                Optional.of(key("next-2026-10", nextPair))));

        assertThat(keys(jwks)).containsExactly(
                expectedJwk("active-2026-04", activePair),
                expectedJwk("next-2026-10", nextPair));
    }

    /**
     * A coordinate whose leading byte is zero is still published at its full 48 bytes (64
     * base64url characters), as RFC 7518 requires; a verifier that reads a shorter value sees a
     * different point. About one key in 256 has such an {@code x}.
     */
    @Test
    void aCoordinateWithALeadingZeroByteKeepsItsFullLength() {
        KeyPair shortX = EpicTestKeys.p384KeyPair();
        while (((ECPublicKey) shortX.getPublic()).getW().getAffineX().bitLength() > 376) {
            shortX = EpicTestKeys.p384KeyPair();
        }
        EpicJwks jwks = EpicJwks.of(new EpicSigningKeys(key("active-2026-04", shortX),
                Optional.empty()));

        assertThat(keys(jwks).get(0).get("x"))
                .isEqualTo(EpicTestKeys.base64Url48(((ECPublicKey) shortX.getPublic()).getW().getAffineX()));
    }

    /** No private parameter of any kind — {@code d} for an EC key — ever leaves the service. */
    @Test
    void noPublishedKeyCarriesAPrivateParameter() {
        EpicJwks jwks = EpicJwks.of(new EpicSigningKeys(key("active-2026-04", activePair),
                Optional.of(key("next-2026-10", nextPair))));

        assertThat(keys(jwks)).hasSize(2).allSatisfy(jwk -> assertThat(jwk)
                .containsOnlyKeys("kty", "crv", "x", "y", "kid", "use", "alg"));
    }

    /** While Epic Login is off there are no keys, so there is nothing to publish. */
    @Test
    void withNoSigningKeysTheJwksIsEmpty() {
        assertThat(EpicJwks.none().document()).isEqualTo(Map.of("keys", List.of()));
    }

    private static Map<String, Object> expectedJwk(String kid, KeyPair pair) {
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        return Map.of(
                "kty", "EC",
                "crv", "P-384",
                "x", EpicTestKeys.base64Url48(publicKey.getW().getAffineX()),
                "y", EpicTestKeys.base64Url48(publicKey.getW().getAffineY()),
                "kid", kid,
                "use", "sig",
                "alg", "ES384");
    }

    private static EpicSigningKey key(String kid, KeyPair pair) {
        return new EpicSigningKey(kid, (ECPrivateKey) pair.getPrivate());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keys(EpicJwks jwks) {
        return (List<Map<String, Object>>) jwks.document().get("keys");
    }

    private static List<Object> kids(EpicJwks jwks) {
        return Arrays.asList(keys(jwks).stream().map(jwk -> jwk.get("kid")).toArray());
    }
}
