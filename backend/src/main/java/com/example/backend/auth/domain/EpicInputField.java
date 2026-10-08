package com.example.backend.auth.domain;

/**
 * An input a browser hands an Epic Login that can be refused for its own sake (D10, D18): named
 * in the refusal's log, and never with its value (D22).
 */
public enum EpicInputField {

    /** The launch URL's {@code iss}, which must be exactly the FHIR base (D10). */
    ISS("iss"),

    /** The launch URL's opaque {@code launch} (D18). */
    LAUNCH("launch"),

    /** The callback's authorization {@code code} (D18). */
    CODE("code");

    private final String value;

    EpicInputField(String value) {
        this.value = value;
    }

    /** The field as the log names it: its parameter name. */
    public String value() {
        return value;
    }
}
