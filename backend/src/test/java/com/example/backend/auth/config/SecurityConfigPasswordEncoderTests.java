package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password encoder bean directly, isolated from the beans around it: the
 * stored-hash prefix and the Argon2id parameters are properties of the bean itself, not of how
 * it is wired into authentication.
 */
class SecurityConfigPasswordEncoderTests {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void aNewlyEncodedHashCarriesTheArgon2idPrefix() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("whatever");

        assertThat(hash).startsWith("{argon2id}");
    }

    /**
     * The parameters {@code /docs/domain/passwords.md} specifies — {@code m=19456} KiB,
     * {@code t=2}, {@code p=1} — with a 16-byte salt and a 32-byte hash, which unpadded Base64
     * writes as 22 and 43 characters.
     */
    @Test
    void aNewlyEncodedHashIsArgon2idAtThePolicysParameters() {
        String hash = config.passwordEncoder().encode("whatever");

        assertThat(hash).matches(
                "\\{argon2id}\\$argon2id\\$v=19\\$m=19456,t=2,p=1"
                        + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    }

    @Test
    void theFactoryHashesAtTheCostsItIsGiven() {
        String hash = SecurityConfig.normalizingArgon2id(8, 1).encode("whatever");

        assertThat(hash).matches(
                "\\{argon2id}\\$argon2id\\$v=19\\$m=8,t=1,p=1"
                        + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    }

    /** A composed {@code é} (U+00E9) and a decomposed one ({@code e} + U+0301) are one password. */
    @Test
    void theFactorysEncoderComparesTheNormalizedPassword() {
        PasswordEncoder encoder = SecurityConfig.normalizingArgon2id(8, 1);

        String hash = encoder.encode("caf\u00e9-password");

        assertThat(encoder.matches("cafe\u0301-password", hash)).isTrue();
    }

    @Test
    void theFactorysEncoderRefusesAnotherPassword() {
        PasswordEncoder encoder = SecurityConfig.normalizingArgon2id(8, 1);

        String hash = encoder.encode("caf\u00e9-password");

        assertThat(encoder.matches("cafe-password", hash)).isFalse();
    }
}
