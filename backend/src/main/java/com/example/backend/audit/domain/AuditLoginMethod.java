package com.example.backend.audit.domain;

/**
 * How a Login proved who was signing in (Epic Login spec, D15): the login method a
 * {@code LOGIN_SUCCESS} and a {@code LOGIN_FAILURE} carry, and the operational
 * {@code session-start} with them.
 *
 * <p>A closed set, as every classification crossing the {@link AuditTrail} is. While a
 * deployment serves exactly one Epic organisation (D4), {@link #SSO} means Epic Login; it is
 * not named after Epic so that the recorded vocabulary does not change if the organisation
 * behind it ever does.
 */
public enum AuditLoginMethod {

    /** Password Login: submitted credentials, checked here. */
    PASSWORD("password"),

    /** Epic Login: an EHR launch, the clinician proven by Epic's {@code id_token}. */
    SSO("sso");

    private final String value;

    AuditLoginMethod(String value) {
        this.value = value;
    }

    /** The method as it is recorded and reported: {@code password} or {@code sso}. */
    public String value() {
        return value;
    }
}
