package com.example.backend.authorization.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One fine-grained power a protected action requires.
 *
 * <p>The set is CLOSED and lives here, in code, rather than in data: a deployment chooses which
 * Group carries which Permission, never which Permissions exist. A new Permission is a code
 * change because it only means something once a protected action declares it, and only code can
 * do that. A stored name that is not one of these fails startup — see
 * {@link GroupPermissionValues} — so a removed Permission can never silently linger on a Group.
 *
 * <p>Two flags say where a Permission may go. {@link #delegatable()} is what a Group may carry;
 * {@link #tokenGrantable()} is what a connector token may carry. The Superuser is not a member of
 * this enum: it is a session authority decided by direct membership of the Admin group, so it can
 * never be put on a Group.
 *
 * <p>The wire and configuration spelling is {@link #value()} ({@code user:read}), not the
 * constant's name, and the order a caller is told its Permissions in is {@link #BY_VALUE}: sorted
 * by that spelling, so the order is a fact about the names and not about declaration order here.
 */
public enum Permission {

    USER_READ("user:read", true, true),
    USER_WRITE("user:write", false, true),
    ACCOUNT_UNLOCK("account:unlock", true, false),
    GROUP_READ("group:read", true, true),
    GROUP_WRITE("group:write", false, true),
    AUDIT_READ("audit:read", true, false),
    CONNECTOR_READ("connector:read", true, false),
    OPS_READ("ops:read", true, false),
    PATIENT_READ("patient:read", false, false);

    /** Sorted by the spelling a caller sees, which is the order {@code /api/auth/me} reports. */
    public static final Comparator<Permission> BY_VALUE = Comparator.comparing(Permission::value);

    private final String value;
    private final boolean delegatable;
    private final boolean tokenGrantable;

    Permission(String value, boolean delegatable, boolean tokenGrantable) {
        this.value = value;
        this.delegatable = delegatable;
        this.tokenGrantable = tokenGrantable;
    }

    /** The Permission's name as configuration, the session and the API spell it. */
    public String value() {
        return value;
    }

    /**
     * Whether a Group may carry this Permission. The Permissions that are not delegatable are
     * {@code patient:read}, which every active User holds as the baseline and no Group confers,
     * and the write halves {@code user:write} and {@code group:write}, which only a connector
     * token carries: a Group holding either would be a Superuser by another name. The helpdesk
     * power, Unlock and the forced password change, is {@code account:unlock}, which is delegatable
     * because neither action can escalate (ADR 0015).
     */
    public boolean delegatable() {
        return delegatable;
    }

    /**
     * Whether a connector token may carry this Permission: the SCIM chain's vocabulary, which is
     * enforced separately from the session's. A token holding a write half is Superuser-equivalent,
     * which is why only the Superuser mints one.
     */
    public boolean tokenGrantable() {
        return tokenGrantable;
    }

    /**
     * Whether only a connector token may carry this Permission: {@linkplain #tokenGrantable()
     * token-grantable} and not {@linkplain #delegatable() delegatable}. No session holds one —
     * not a Superuser's either — so a Group stored carrying one fails startup
     * ({@link GroupPermissionValues}).
     */
    public boolean tokenOnly() {
        return tokenGrantable && !delegatable;
    }

    /**
     * Every {@linkplain #delegatable() delegatable} Permission: what a Superuser's session is
     * expanded with at sign-in, in code, rather than from a stored list or a role hierarchy.
     */
    public static Set<Permission> allDelegatable() {
        return Arrays.stream(values())
                .filter(Permission::delegatable)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }

    /**
     * These Permissions as the wire spells them ({@link #value()}), in {@link #BY_VALUE} order: the
     * one way a set of Permissions becomes a list a caller, a log or the audit trail sees.
     */
    public static List<String> valuesOf(Collection<Permission> permissions) {
        return permissions.stream().sorted(BY_VALUE).map(Permission::value).toList();
    }

    /** The Permission spelled this way, or empty when no Permission is. Case-sensitive. */
    public static Optional<Permission> fromValue(String value) {
        return Arrays.stream(values())
                .filter(permission -> permission.value.equals(value))
                .findFirst();
    }
}
