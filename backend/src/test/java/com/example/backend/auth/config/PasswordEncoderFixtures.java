package com.example.backend.auth.config;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The deployment's normalizing Argon2id encoder ({@link SecurityConfig#normalizingArgon2id}) at
 * Argon2's minimum costs, for the unit tests that hash and verify many passwords and, through
 * {@code ContainerTestConfiguration}, every test context: the same
 * normalization, delegation and {@code {argon2id}} prefix as the bean, without the policy's
 * deliberate cost on every call. {@code SecurityConfigPasswordEncoderTests} holds the bean to the
 * policy's parameters; nothing here asserts on how long a hash takes.
 */
public final class PasswordEncoderFixtures {

    /** Argon2's floor: 8 KiB per lane, one pass. */
    private static final PasswordEncoder CHEAP_ARGON2ID = SecurityConfig.normalizingArgon2id(8, 1);

    private PasswordEncoderFixtures() {
    }

    /** The shipped encoder's shape at Argon2's minimum memory cost and iteration count. */
    public static PasswordEncoder cheapArgon2id() {
        return CHEAP_ARGON2ID;
    }
}
