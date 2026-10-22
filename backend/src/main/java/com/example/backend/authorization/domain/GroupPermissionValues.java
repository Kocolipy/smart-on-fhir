package com.example.backend.authorization.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the Permissions Groups carry out of their stored names (ADR 0015).
 *
 * <p>Storage holds a Permission by its name, and the vocabulary is closed and lives in code, so a
 * stored name can stop naming one: a Permission removed from code leaves its rows behind unless a
 * migration deletes them. Only a {@linkplain Permission#delegatable() delegatable} Permission is
 * one a Group may carry; a token-only write half or the baseline {@code patient:read} on a Group
 * would be a power no session is meant to hold. Startup refuses either, naming every problem in
 * one message, so a deployment never runs with a Group meaning something other than it says.
 */
public final class GroupPermissionValues {

    private GroupPermissionValues() {
    }

    /**
     * Each Group's Permissions, or {@link InvalidGroupPermissionsException} naming every stored
     * value that is unknown or that no Group may carry. A Group with no row is absent from the
     * answer, because it carries nothing.
     */
    public static Map<UUID, Set<Permission>> validated(Collection<StoredGroupPermission> stored) {
        List<String> problems = new ArrayList<>();
        Map<UUID, Set<Permission>> carried = new LinkedHashMap<>();
        for (StoredGroupPermission row : stored) {
            Optional<Permission> permission = Permission.fromValue(row.value());
            if (permission.isEmpty()) {
                problems.add("Group " + row.groupId() + " carries unknown Permission '"
                        + row.value() + "'");
            } else if (!permission.get().delegatable()) {
                problems.add("Group " + row.groupId() + " carries Permission '" + row.value()
                        + "', which no Group may carry");
            } else {
                carried.computeIfAbsent(row.groupId(), group -> EnumSet.noneOf(Permission.class))
                        .add(permission.get());
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidGroupPermissionsException(problems);
        }
        Map<UUID, Set<Permission>> unmodifiable = new LinkedHashMap<>();
        carried.forEach((group, permissions) ->
                unmodifiable.put(group, Collections.unmodifiableSet(permissions)));
        return Collections.unmodifiableMap(unmodifiable);
    }
}
