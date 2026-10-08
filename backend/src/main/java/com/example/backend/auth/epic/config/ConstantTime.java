package com.example.backend.auth.epic.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one comparison of a value Epic echoes back — the callback's {@code state}, the
 * {@code id_token}'s {@code nonce} — against the one this service sent (D27): in time that does
 * not depend on where the two first differ, so a forger learns nothing from how long a refusal
 * took.
 */
final class ConstantTime {

    private ConstantTime() {
    }

    /** Whether {@code received} is exactly {@code expected}; never when either is absent. */
    static boolean equals(String received, String expected) {
        if (received == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(received.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
