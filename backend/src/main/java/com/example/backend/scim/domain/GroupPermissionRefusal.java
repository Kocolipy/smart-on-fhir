package com.example.backend.scim.domain;

import com.example.backend.authorization.domain.Permission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Why the Group Permissions store refuses to give a Group Permissions (ADR 0015), as a closed set,
 * in the order they are read: a write that is several of these at once is refused for the first.
 *
 * <p>The rule is the store's, so every writer is held to it whichever adapter it reaches; a writer
 * that reports a refusal of its own reads which one it is from here, and reports it as it sees fit.
 */
public sealed interface GroupPermissionRefusal {

    /**
     * The write names Permissions no Group may carry — a token-only write half, or the baseline
     * {@code patient:read}. Read first, before the Group is looked up: what is asked for is wrong
     * whichever Group it is asked for.
     *
     * @param permissions every Permission the write names that is not
     *                    {@linkplain Permission#delegatable() delegatable}
     */
    record NotDelegatable(Set<Permission> permissions) implements GroupPermissionRefusal {

        public NotDelegatable {
            permissions = Set.copyOf(permissions);
        }
    }

    /** No Group has this id, or none does any longer. */
    record UnknownGroup() implements GroupPermissionRefusal {
    }

    /**
     * The Group is the Admin group, recognised by its reservation marker. It carries no Permission:
     * its direct members are the Superuser, which is no Permission at all.
     */
    record AdminGroup() implements GroupPermissionRefusal {
    }

    /**
     * The first refusal of giving {@code permissions} to a Group, or empty when the write may go
     * through. Each adapter answers the two questions about the Group from its own storage; the
     * order they are read in is this one.
     *
     * @param permissions the Permissions the write names
     * @param groupExists whether a Group has the id
     * @param adminGroup  whether that Group holds the Admin group's reservation marker
     */
    static Optional<GroupPermissionRefusal> of(
            Set<Permission> permissions, boolean groupExists, boolean adminGroup) {
        Set<Permission> notDelegatable = EnumSet.noneOf(Permission.class);
        permissions.stream().filter(permission -> !permission.delegatable())
                .forEach(notDelegatable::add);
        if (!notDelegatable.isEmpty()) {
            return Optional.of(new NotDelegatable(notDelegatable));
        }
        if (!groupExists) {
            return Optional.of(new UnknownGroup());
        }
        if (adminGroup) {
            return Optional.of(new AdminGroup());
        }
        return Optional.empty();
    }
}
