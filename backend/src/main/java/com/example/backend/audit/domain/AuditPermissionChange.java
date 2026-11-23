package com.example.backend.audit.domain;

import com.example.backend.authorization.domain.Permission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a membership change did to a User's power: its effective Permissions before and after, and
 * whether the change granted or removed the Superuser (ADR 0015).
 *
 * <p>Effective means what the User's direct Group memberships confer, the Superuser's expansion to
 * every delegatable Permission included, {@code patient:read} among them when a Group confers it.
 * Baseline access is no Permission and is not part of it: no membership confers it, so no
 * membership change moves it.
 *
 * @param before           what the User held before the change
 * @param after            what it holds after
 * @param superuserChanged whether the change granted or removed the Superuser
 */
public record AuditPermissionChange(
        Set<Permission> before, Set<Permission> after, boolean superuserChanged) {

    public AuditPermissionChange {
        before = copy(before);
        after = copy(after);
    }

    /** The Permissions held before, by their wire spelling, sorted. */
    List<String> beforeValues() {
        return values(before);
    }

    /** The Permissions held after, by their wire spelling, sorted. */
    List<String> afterValues() {
        return values(after);
    }

    private static Set<Permission> copy(Set<Permission> permissions) {
        Set<Permission> copy = EnumSet.noneOf(Permission.class);
        copy.addAll(permissions);
        return Collections.unmodifiableSet(copy);
    }

    private static List<String> values(Set<Permission> permissions) {
        return Permission.valuesOf(permissions);
    }
}
