package com.example.backend.auth.epic;

import java.security.interfaces.ECPrivateKey;

/**
 * One client-assertion signing key: the EC P-384 private key and the {@code kid} the JWKS
 * publishes it under. The two always travel together — a key is never used or published without
 * its {@code kid} (D14).
 *
 * @param keyId      the key's {@code kid}, a public label
 * @param privateKey the key itself, read from PEM once at startup
 */
public record EpicSigningKey(String keyId, ECPrivateKey privateKey) {

    /** The {@code kid} alone. The generated form would print the private key (D22). */
    @Override
    public String toString() {
        return "EpicSigningKey[keyId=" + keyId + ", privateKey=[REDACTED]]";
    }
}
