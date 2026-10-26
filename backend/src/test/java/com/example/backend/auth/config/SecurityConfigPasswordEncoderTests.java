package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password encoder bean directly, isolated from the beans around it: the
 * stored-hash prefix is a property of the bean itself, not of how it is wired
 * into authentication.
 */
class SecurityConfigPasswordEncoderTests {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void aNewlyEncodedHashCarriesTheArgon2idPrefix() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("whatever");

        assertThat(hash).startsWith("{argon2id}");
    }

    @Test
    void aHashItProducedVerifiesAgainstTheOriginalPassword() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("correct-password");

        assertThat(encoder.matches("correct-password", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }
}
