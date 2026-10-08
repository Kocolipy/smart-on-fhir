package com.example.backend.auth.domain;

/**
 * The bound an Epic Login input broke (D10, D18): what the refusal's log names in place of the
 * value, which it never carries (D22).
 */
public enum EpicInputRule {

    /** Absent, or empty: there is nothing to check. */
    MISSING("missing"),

    /** Longer than D18 allows. */
    LENGTH("length"),

    /** A character outside printable ASCII, whitespace included. */
    CHARSET("charset"),

    /** Present, and not exactly the one value allowed: {@code iss} (D10). */
    MISMATCH("mismatch");

    private final String value;

    EpicInputRule(String value) {
        this.value = value;
    }

    /** The rule as the log names it. */
    public String value() {
        return value;
    }
}
