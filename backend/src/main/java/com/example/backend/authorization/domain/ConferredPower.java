package com.example.backend.authorization.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The conferred power of a set of direct Group memberships: the User's effective Permissions and
 * whether it is the Superuser, answered together by {@link GroupPermissions} so that no caller
 * asks for one without the other (ADR 0015).
 *
 * <p>Effective means the Superuser's expansion is included: a direct member of the Admin group
 * holds every {@linkplain Permission#delegatable() delegatable} Permission, expanded here in code,
 * beside whatever its other Groups carry. {@code patient:read} is never part of it — every active
 * User holds the baseline whatever its Groups, so no membership confers it.
 *
 * @param permissions the Permissions the memberships confer, the Superuser's expansion included
 * @param superuser   whether the memberships include the Admin group
 */
public record ConferredPower(Set<Permission> permissions, boolean superuser) {

    /** What no membership confers: nothing beyond the baseline, and not the Superuser. */
    public static final ConferredPower NONE = new ConferredPower(Set.of(), false);

    public ConferredPower {
        Set<Permission> copy = EnumSet.noneOf(Permission.class);
        copy.addAll(permissions);
        permissions = Collections.unmodifiableSet(copy);
    }

    /**
     * The Superuser rule, in its one place: a User holding exactly {@code memberships} is the
     * Superuser when they include {@code adminGroup}, the Group carrying the reservation marker —
     * direct membership only, never by name — and holds the union of what its Groups carry.
     *
     * @param carried    what each Group carries, at least for every Group in {@code memberships}
     * @param adminGroup the Admin group, or empty when no Group carries the marker
     */
    static ConferredPower of(Collection<UUID> memberships, Map<UUID, Set<Permission>> carried,
            Optional<UUID> adminGroup) {
        boolean superuser = adminGroup.filter(memberships::contains).isPresent();
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        memberships.forEach(group -> held.addAll(carried.getOrDefault(group, Set.of())));
        if (superuser) {
            held.addAll(Permission.allDelegatable());
        }
        return new ConferredPower(held, superuser);
    }
}
