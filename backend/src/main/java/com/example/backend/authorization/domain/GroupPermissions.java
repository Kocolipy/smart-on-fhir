package com.example.backend.authorization.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a User's direct Group memberships confer: the one question sign-in, the Groups projection
 * and the audit trail all ask of the Groups (ADR 0015).
 *
 * <p>A Group's Permissions are data, stored per Group. A Group that carries none confers nothing,
 * and the Admin group carries none: its direct members are the Superuser, expanded here in code.
 *
 * <p>Nothing here is the baseline: {@code patient:read} is held by every active User whatever its
 * Groups, so no membership confers it and no membership change moves it.
 */
public interface GroupPermissions {

    /**
     * The Permissions each of these Groups carries, before the Superuser's expansion. A Group that
     * carries none, or that does not exist, is absent from the answer.
     */
    Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds);

    /** Whether these Groups include the Admin group, whose direct members are the Superuser. */
    boolean confersSuperuser(Collection<UUID> groupIds);

    /** The union of the Permissions these Groups carry, before the Superuser's expansion. */
    default Set<Permission> permissionsOf(Collection<UUID> groupIds) {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        carriedBy(groupIds).values().forEach(held::addAll);
        return Collections.unmodifiableSet(held);
    }

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

    /**
     * These answers, except that {@code groupId} still carries what it carried a moment ago: for a
     * write that has just removed the Group itself — a deletion, whose cascade takes the Group's
     * Permissions with it — and must still say what its former members held before it.
     */
    default GroupPermissions remembering(UUID groupId, Set<Permission> carried) {
        GroupPermissions current = this;
        return new GroupPermissions() {
            @Override
            public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
                Map<UUID, Set<Permission>> answer = new HashMap<>(current.carriedBy(groupIds));
                if (groupIds.contains(groupId) && !carried.isEmpty()) {
                    answer.put(groupId, carried);
                }
                return answer;
            }

            @Override
            public boolean confersSuperuser(Collection<UUID> groupIds) {
                return current.confersSuperuser(groupIds);
            }
        };
    }
}
