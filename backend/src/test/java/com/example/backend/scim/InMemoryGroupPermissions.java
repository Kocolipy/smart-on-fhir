package com.example.backend.scim;

import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.GroupPermissionValues;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.StoredGroupPermission;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Groups' stored Permissions in memory, for tests that build services by hand: the
 * {@code group_permission} table and the Admin group's reservation marker, as the SQL Server
 * adapter answers them.
 *
 * <p>Over a directory ({@link #over}) it behaves as the database does: the Admin group is the
 * Group holding the reservation marker, and a Group that no longer exists carries nothing, its
 * rows having gone with it. {@link #superuserOnly()} stands alone, for a test with no directory
 * of its own, and recognises the Admin group by the id the seed creates it under.
 */
public final class InMemoryGroupPermissions implements GroupPermissions, ScimGroupPermissionStore {

    private final Predicate<UUID> adminGroup;
    private final Predicate<UUID> exists;
    private final Map<UUID, Set<String>> rows = new LinkedHashMap<>();

    private InMemoryGroupPermissions(Predicate<UUID> adminGroup, Predicate<UUID> exists) {
        this.adminGroup = adminGroup;
        this.exists = exists;
    }

    /** No Group carries anything; the Group under the seeded Admin group's id is the Superuser's. */
    public static InMemoryGroupPermissions superuserOnly() {
        return new InMemoryGroupPermissions(
                TestGroupPermissions.SUPERUSER_GROUP_ID::equals, group -> true);
    }

    /** The Groups of {@code groups}: the Admin group by its marker, a deleted Group's rows gone. */
    public static InMemoryGroupPermissions over(ScimGroupRepository groups) {
        return new InMemoryGroupPermissions(
                group -> groups.findById(group)
                        .map(found -> found.reservedName() == ReservedResourceName.ADMIN_GROUP)
                        .orElse(false),
                group -> groups.findById(group).isPresent());
    }

    /** This Group carries these Permissions too, as though a row were stored for each. */
    public InMemoryGroupPermissions carrying(UUID groupId, Permission... permissions) {
        for (Permission permission : permissions) {
            store(groupId, permission.value());
        }
        return this;
    }

    /** A row exactly as storage could hold it, a name the vocabulary lacks included. */
    public InMemoryGroupPermissions store(UUID groupId, String value) {
        rows.computeIfAbsent(groupId, group -> new LinkedHashSet<>()).add(value);
        return this;
    }

    @Override
    public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
        List<StoredGroupPermission> found = new ArrayList<>();
        for (UUID groupId : new LinkedHashSet<>(groupIds)) {
            if (exists.test(groupId)) {
                rows.getOrDefault(groupId, Set.of())
                        .forEach(value -> found.add(new StoredGroupPermission(groupId, value)));
            }
        }
        return GroupPermissionValues.validated(found);
    }

    @Override
    public boolean confersSuperuser(Collection<UUID> groupIds) {
        return groupIds.stream().anyMatch(adminGroup);
    }

    @Override
    public List<StoredGroupPermission> findAll() {
        List<StoredGroupPermission> all = new ArrayList<>();
        rows.forEach((groupId, values) -> {
            if (exists.test(groupId)) {
                values.forEach(value -> all.add(new StoredGroupPermission(groupId, value)));
            }
        });
        return all;
    }

    @Override
    public boolean carriesAny(UUID groupId) {
        return exists.test(groupId) && !rows.getOrDefault(groupId, Set.of()).isEmpty();
    }

    @Override
    public void add(UUID groupId, Set<Permission> permissions) {
        permissions.forEach(permission -> store(groupId, permission.value()));
    }

    @Override
    public void replace(UUID groupId, Set<Permission> permissions) {
        rows.remove(groupId);
        add(groupId, permissions);
    }
}
