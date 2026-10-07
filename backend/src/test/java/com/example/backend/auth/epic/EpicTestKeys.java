package com.example.backend.auth.epic;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.function.Supplier;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * EC private keys minted per test run and armoured as PKCS#8 PEM, the form
 * {@code APP_EPIC_CLIENT_KEY} carries. Generated rather than checked in, so no private key — not
 * even a throwaway one — is ever committed to this public repository.
 */
public final class EpicTestKeys {

    private EpicTestKeys() {
    }

    /** A fresh EC P-384 private key, the curve Epic's ES384 client assertion needs. */
    public static String p384Pem() {
        return pem("secp384r1");
    }

    /** A fresh EC P-256 private key: well-formed, but on the wrong curve. */
    public static String p256Pem() {
        return pem("secp256r1");
    }

    /**
     * A fresh EC P-384 key pair. Its public half is what the JWKS must publish for the private
     * half, worked out here by the JDK's key generator rather than by the code under test.
     */
    public static KeyPair p384KeyPair() {
        return keyPair("secp384r1");
    }

    /** A fresh EC P-256 key pair: a real EC key, on a curve ES384 cannot sign with. */
    public static KeyPair p256KeyPair() {
        return keyPair("secp256r1");
    }

    /** {@code pair}'s private key armoured as PKCS#8 PEM. */
    public static String pem(KeyPair pair) {
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8);
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    /**
     * Registers Epic Login on, with every setting it needs: fixed endpoints and client id, and the
     * given active and next private keys under the kids {@code active-2026-04} and
     * {@code next-2026-10}.
     */
    public static void epicLoginOn(DynamicPropertyRegistry registry, Supplier<Object> activePem,
            Supplier<Object> nextPem) {
        registry.add("app.epic.enabled", () -> "true");
        registry.add("app.epic.fhir-base", () -> "https://fhir.example.org/api/FHIR/R4");
        registry.add("app.epic.oauth-issuer", () -> "https://fhir.example.org/oauth2");
        registry.add("app.epic.client-id", () -> "epic-client-id");
        registry.add("app.epic.redirect-uri",
                () -> "https://app.example.org/api/auth/epic/callback");
        registry.add("app.epic.client-key", activePem);
        registry.add("app.epic.client-key-id", () -> "active-2026-04");
        registry.add("app.epic.client-next-key", nextPem);
        registry.add("app.epic.client-next-key-id", () -> "next-2026-10");
    }

    /**
     * {@code value} the way a JWK writes a P-384 field element (RFC 7518 §6.2.1.2, §6.2.2.1): a
     * 48-byte big-endian octet string, left-padded with zeros, base64url without padding.
     */
    public static String base64Url48(BigInteger value) {
        byte[] magnitude = value.toByteArray();
        byte[] fixed = new byte[48];
        int length = Math.min(magnitude.length, 48);
        System.arraycopy(magnitude, magnitude.length - length, fixed, 48 - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private static String pem(String curve) {
        return pem(keyPair(curve));
    }

    private static KeyPair keyPair(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
