package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password encoder bean directly, isolated from the beans around it: the
 * stored-hash prefix, and that no other decoder is reachable through it, are
 * properties of the bean itself, not of how it is wired into authentication.
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
     * A hash produced by any other encoder — bcrypt named explicitly, since it
     * is the common default a delegating encoder would otherwise register — has
     * no decoder to read it: this Spring Security version's
     * {@link org.springframework.security.crypto.password.DelegatingPasswordEncoder}
     * refuses an unmapped id loudly, by throwing, rather than by silently
     * returning {@code false}. Either behaviour would satisfy "no BCrypt
     * decoder remains registered", but throwing is the actual, stronger
     * guarantee this version gives, so that is what the test asserts.
     */
    @Test
    void aBcryptHashHasNoRegisteredDecoderToVerifyAgainst() {
        PasswordEncoder encoder = config.passwordEncoder();
        String bcryptHash = "{bcrypt}$2a$10$AbsentAbsentAbsentAbsentAeH3W8kZ4jL2qYyv5t8mR0oQvVnrhS8i2";

        assertThatThrownBy(() -> encoder.matches("whatever", bcryptHash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHashItProducedVerifiesAgainstTheOriginalPassword() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("correct-password");

        assertThat(encoder.matches("correct-password", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }
}
