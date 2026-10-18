package com.example.backend.authorization.domain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * One fine-grained power a protected action requires.
 *
 * <p>The set is CLOSED and lives here, in code, rather than in configuration: a deployment chooses
 * which Role holds which Permission, never which Permissions exist. A new Permission is a code
 * change because it only means something once a protected action declares it, and only code can
 * do that. A name in configuration that is not one of these fails startup — see
 * {@link RoleMapping} — so a typo can never silently grant nothing.
 *
 * <p>The wire and configuration spelling is {@link #value()} ({@code user:read}), not the
 * constant's name, and the order a caller is told its Permissions in is {@link #BY_VALUE}: sorted
 * by that spelling, so the order is a fact about the names and not about declaration order here.
 */
public enum Permission {

    USER_READ("user:read"),
    USER_WRITE("user:write"),
    GROUP_READ("group:read"),
    GROUP_WRITE("group:write"),
    AUDIT_READ("audit:read"),
    CONNECTOR_READ("connector:read"),
    CONNECTOR_WRITE("connector:write"),
    CONNECTOR_TOKEN("connector:token"),
    OPS_READ("ops:read"),
    COUNTER_READ("counter:read"),
    COUNTER_WRITE("counter:write"),
    PATIENT_READ("patient:read");

    /** Sorted by the spelling a caller sees, which is the order {@code /api/auth/me} reports. */
    public static final Comparator<Permission> BY_VALUE = Comparator.comparing(Permission::value);

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    /** The Permission's name as configuration, the session and the API spell it. */
    public String value() {
        return value;
    }

    /** The Permission spelled this way, or empty when no Permission is. Case-sensitive. */
    public static Optional<Permission> fromValue(String value) {
        return Arrays.stream(values())
                .filter(permission -> permission.value.equals(value))
                .findFirst();
    }
}
