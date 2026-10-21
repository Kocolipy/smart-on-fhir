package com.example.backend.authorization.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a User's direct Group memberships confer: the one question sign-in and the audit trail both
 * ask of the Groups (ADR 0015).
 *
 * <p>The seam a source of Group Permissions sits behind. While Roles exist, {@link RoleMapping} is
 * it, and a Group confers what the Role mapped to it holds; once Groups carry their Permissions in
 * the database, another implementation answers the same questions and nothing that asks them
 * changes.
 *
 * <p>Nothing here is the baseline: {@code patient:read} is held by every active User whatever its
 * Groups, so no membership confers it and no membership change moves it.
 */
public interface GroupPermissions {

    /**
     * The union of the Permissions these Groups carry, before the Superuser's expansion. A Group
     * that carries none, or that the source does not know, confers nothing.
     */
    Set<Permission> permissionsOf(Collection<UUID> groupIds);

    /** Whether these Groups include the Admin group, whose direct members are the Superuser. */
    boolean confersSuperuser(Collection<UUID> groupIds);

    /**
     * What a User holding exactly these direct memberships holds beyond the baseline: the
     * Permissions they carry and, for the Superuser, every {@linkplain Permission#delegatable()
     * delegatable} Permission — expanded here in code, as the Superuser's session is at sign-in.
     */
    default Set<Permission> effectivePermissionsOf(Collection<UUID> groupIds) {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        held.addAll(permissionsOf(groupIds));
        if (confersSuperuser(groupIds)) {
            held.addAll(Permission.allDelegatable());
        }
        return Collections.unmodifiableSet(held);
    }

    /**
     * Whether this Group's membership is a change of power: it is the Admin group, or it carries a
     * Permission. A Group that confers nothing moves nobody's Permissions by its membership.
     */
    default boolean confersPermissions(UUID groupId) {
        return !effectivePermissionsOf(List.of(groupId)).isEmpty();
    }
}
