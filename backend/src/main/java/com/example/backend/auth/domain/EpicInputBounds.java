package com.example.backend.auth.domain;

import java.util.Optional;

/**
 * D18's bounds on the two opaque values a browser hands an Epic Login: {@code launch} on the
 * launch URL and {@code code} on the callback. Each must be 1–8192 characters of printable ASCII
 * with no whitespace — {@code U+0021} to {@code U+007E} — and is refused otherwise, before it is
 * held or sent anywhere.
 *
 * <p>A check answers with the {@link EpicInputRule} the value broke, never with the value: the
 * rule is what the log names (flow step 1, D22).
 */
public final class EpicInputBounds {

    /** The longest {@code launch} or {@code code} accepted. */
    public static final int MAX_LENGTH = 8192;

    private EpicInputBounds() {
    }

    /** The D18 rule {@code value} breaks, or empty when it is within bounds. */
    public static Optional<EpicInputRule> brokenBy(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.of(EpicInputRule.MISSING);
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.of(EpicInputRule.LENGTH);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '!' || c > '~') {
                return Optional.of(EpicInputRule.CHARSET);
            }
        }
        return Optional.empty();
    }

    /**
     * The D10 rule {@code iss} breaks against {@code expected}, or empty when it is exactly
     * {@code expected}: compared as a string, never normalized — no case folding, no
     * trailing-slash trimming.
     */
    public static Optional<EpicInputRule> issBrokenBy(String iss, String expected) {
        if (iss == null || iss.isEmpty()) {
            return Optional.of(EpicInputRule.MISSING);
        }
        return expected.equals(iss) ? Optional.empty() : Optional.of(EpicInputRule.MISMATCH);
    }
}
