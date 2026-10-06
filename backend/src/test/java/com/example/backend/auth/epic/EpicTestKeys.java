package com.example.backend.auth.epic;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

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

    private static String pem(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
            String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8);
            return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
