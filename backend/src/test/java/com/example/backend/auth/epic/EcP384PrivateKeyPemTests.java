package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.interfaces.ECPrivateKey;
import org.junit.jupiter.api.Test;

/**
 * The PEM reader on its own, as the signing-key step will call it. Its refusals are covered
 * through startup in {@code EpicLoginConfigTests}; these are the two answers only a direct
 * caller can see.
 */
class EcP384PrivateKeyPemTests {

    @Test
    void aP384KeyIsReadAsAKeyOnTheP384Curve() {
        ECPrivateKey key = EcP384PrivateKeyPem.parse(EpicTestKeys.p384Pem()).orElseThrow();

        assertThat(key.getParams().getCurve().getField().getFieldSize()).isEqualTo(384);
    }

    @Test
    void anAbsentValueIsNoKey() {
        assertThat(EcP384PrivateKeyPem.parse(null)).isEmpty();
    }
}
