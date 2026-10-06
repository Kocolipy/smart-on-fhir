package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What a signing key says about itself. A record's generated {@code toString} prints every
 * component, and the private key must not go with it into debug output or a log line (D22).
 */
class EpicSigningKeyTests {

    @Test
    void toStringGivesTheKidAndNeverThePrivateKey() {
        EpicSigningKey key = new EpicSigningKey(
                "active-2026-04", EcP384PrivateKeyPem.parse(EpicTestKeys.p384Pem()).orElseThrow());

        assertThat(key.toString())
                .isEqualTo("EpicSigningKey[keyId=active-2026-04, privateKey=[REDACTED]]");
    }
}
