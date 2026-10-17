package com.example.backend.auth.application;

/**
 * The log field names only Epic Login's records carry (ADR 0012), beside the module that writes
 * its endings. The records themselves are built by
 * {@link com.example.backend.observability.LogEvent}'s shapes (ADR 0003); these are only the keys
 * the Login adds to them. {@code app.epic.call} stays in {@code LogEvent}, whose outbound shapes
 * write it.
 */
public final class EpicLoginLogFields {

    /**
     * Which input an Epic Login refused for its bounds — {@code iss}, {@code launch} or
     * {@code code} (ADR 0012, D10, D18) — on the "Epic sign-in refused" record. The field's name
     * only, never its value (D22). Under this service's own {@code app} namespace.
     */
    public static final String EPIC_INPUT_FIELD = "app.epic.input.field";

    /**
     * The bound that input broke: {@code missing}, {@code length}, {@code charset} or
     * {@code mismatch}. Under this service's own {@code app} namespace.
     */
    public static final String EPIC_INPUT_RULE = "app.epic.input.rule";

    /**
     * The MFA factor an accepted Epic Login was made with (ADR 0012, D17), on its
     * {@code user-authentication} record — the same spelling its {@code LOGIN_SUCCESS} carries.
     * Under this service's own {@code app} namespace.
     */
    public static final String MFA_FACTOR = "app.login.mfa_factor";

    private EpicLoginLogFields() {
    }
}
