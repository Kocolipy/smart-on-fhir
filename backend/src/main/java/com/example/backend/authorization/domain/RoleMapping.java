package com.example.backend.authorization.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The deployment's Roles, and which Group confers each: the only source of a User's Permissions.
 *
 * <p>Built once, at startup, from read-only configuration, and never changed while the
 * application runs — there is no operation here or anywhere else that adds a Role or a mapping
 * entry, so no endpoint can grant a Permission and no Permission holder can raise their own. Role
 * ASSIGNMENT is not here: it is Group membership, which the directory owns.
 *
 * <h2>Validated whole, or not at all</h2>
 *
 * <p>{@link #of} refuses every mapping it cannot stand behind, and reports every problem it found
 * in one message rather than the first, so an operator fixing a deployment does not redeploy once
 * per typo. Refused: a Permission name outside {@link Permission}; a {@linkplain
 * Permission#tokenOnly() token-only} Permission, which no session may hold; a Role defined twice;
 * a mapping entry with no Group id, naming the same Group as another, or naming an undefined Role;
 * anything but exactly one Superuser Group; and a Superuser Group whose Role lacks any Permission a
 * session may hold. Whether
 * each Group id RESOLVES to a Group is a question about the directory, which this class cannot
 * see; the startup step that seeds the directory asks it, against {@link #mappedGroupIds()}.
 *
 * <h2>The hash</h2>
 *
 * <p>{@link #hash()} digests a canonical rendering — Roles sorted by name with their Permissions
 * sorted, entries sorted by Group id — so two configurations meaning the same mapping hash the same
 * however their lists were ordered, and any change of meaning changes it. A session records the
 * hash it was issued under, which is how a later startup can tell a session minted under a
 * different mapping.
 */
public final class RoleMapping {

    private final List<Role> roles;
    private final Map<UUID, Role> roleByGroup;
    private final UUID superuserGroupId;
    private final String hash;

    private RoleMapping(
            Map<String, Role> roles, Map<UUID, Role> roleByGroup, UUID superuserGroupId) {
        this.roles = List.copyOf(roles.values());
        this.roleByGroup = Collections.unmodifiableMap(roleByGroup);
        this.superuserGroupId = superuserGroupId;
        this.hash = digest(roles, roleByGroup, superuserGroupId);
    }

    /**
     * The validated mapping, or {@link InvalidRoleMappingException} naming every problem.
     *
     * @param roles  each Role's name and the Permission names it holds
     * @param groups each Group id, the Role it confers, and whether it is the Superuser Group
     */
    public static RoleMapping of(List<RoleDefinition> roles, List<GroupAssignment> groups) {
        List<String> problems = new ArrayList<>();
        Map<String, Role> definedRoles = defineRoles(roles, problems);
        Map<UUID, Role> roleByGroup = new LinkedHashMap<>();
        List<UUID> superuserGroups = new ArrayList<>();
        for (GroupAssignment entry : groups) {
            if (entry.groupId() == null) {
                problems.add("a role mapping entry names no Group id");
                continue;
            }
            if (entry.superuser()) {
                superuserGroups.add(entry.groupId());
            }
            Role role = definedRoles.get(entry.role());
            if (role == null) {
                problems.add("Group " + entry.groupId() + " is mapped to unknown Role '"
                        + entry.role() + "'");
            }
            if (roleByGroup.containsKey(entry.groupId())) {
                problems.add("Group " + entry.groupId() + " is mapped more than once");
            } else {
                // A null Role (unknown, reported above) is kept only until the problems are
                // thrown: the mapping is never built while any problem stands.
                roleByGroup.put(entry.groupId(), role);
            }
        }
        UUID superuserGroupId = superuserGroup(superuserGroups, roleByGroup, problems);
        if (!problems.isEmpty()) {
            throw new InvalidRoleMappingException(problems);
        }
        return new RoleMapping(definedRoles, roleByGroup, superuserGroupId);
    }

    private static Map<String, Role> defineRoles(
            List<RoleDefinition> definitions, List<String> problems) {
        Map<String, Role> defined = new LinkedHashMap<>();
        for (RoleDefinition definition : definitions) {
            if (definition.name() == null || definition.name().isBlank()) {
                problems.add("a Role has no name");
                continue;
            }
            Set<Permission> permissions = EnumSet.noneOf(Permission.class);
            for (String name : definition.permissions() == null
                    ? List.<String>of() : definition.permissions()) {
                Optional<Permission> permission = Permission.fromValue(name);
                if (permission.isPresent() && permission.get().tokenOnly()) {
                    problems.add("Role '" + definition.name() + "' names Permission '" + name
                            + "', which only a connector token may carry");
                } else if (permission.isPresent()) {
                    permissions.add(permission.get());
                } else {
                    problems.add("Role '" + definition.name() + "' names unknown Permission '"
                            + name + "'");
                }
            }
            if (defined.putIfAbsent(definition.name(), new Role(definition.name(), permissions))
                    != null) {
                problems.add("Role '" + definition.name() + "' is defined more than once");
            }
        }
        return defined;
    }

    /**
     * Exactly one Superuser Group, whose Role holds every Permission a session may hold — all but
     * the {@linkplain Permission#tokenOnly() token-only} ones: what guarantees the deployment
     * always has an account able to administer it.
     */
    private static UUID superuserGroup(
            List<UUID> designated, Map<UUID, Role> roleByGroup, List<String> problems) {
        if (designated.isEmpty()) {
            problems.add("no Superuser Group is designated");
            return null;
        }
        if (designated.size() > 1) {
            problems.add("more than one Superuser Group is designated: " + designated.stream()
                    .map(UUID::toString).collect(Collectors.joining(", ")));
            return null;
        }
        UUID superuserGroupId = designated.getFirst();
        Role role = roleByGroup.get(superuserGroupId);
        if (role != null) {
            Set<Permission> missing = EnumSet.allOf(Permission.class);
            missing.removeIf(Permission::tokenOnly);
            missing.removeAll(role.permissions());
            if (!missing.isEmpty()) {
                problems.add("Superuser Group " + superuserGroupId + "'s Role '" + role.name()
                        + "' is missing Permissions: " + missing.stream()
                                .sorted(Permission.BY_VALUE)
                                .map(Permission::value)
                                .collect(Collectors.joining(", ")));
            }
        }
        return superuserGroupId;
    }

    /**
     * The union of the Permissions of the Roles these Groups confer. A Group the mapping does not
     * name confers nothing, so a User in no mapped Group holds none.
     */
    public Set<Permission> permissionsOf(Collection<UUID> groupIds) {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        for (UUID groupId : groupIds) {
            Role role = roleByGroup.get(groupId);
            if (role != null) {
                held.addAll(role.permissions());
            }
        }
        return Collections.unmodifiableSet(held);
    }

    /**
     * The Role this Group confers, or empty for a Group the mapping does not name — which confers
     * nothing, so a change of its membership is no change of anyone's Permissions.
     */
    public Optional<Role> roleOf(UUID groupId) {
        return Optional.ofNullable(roleByGroup.get(groupId));
    }

    /** Every defined Role, in configuration order, a Role no Group confers included. */
    public List<Role> roles() {
        return roles;
    }

    /** Every mapped Group id with the Role it confers, in configuration order. */
    public Map<UUID, Role> assignments() {
        return roleByGroup;
    }

    /** The one Group whose Role holds every Permission a session may hold. */
    public UUID superuserGroupId() {
        return superuserGroupId;
    }

    /** Every Group id the mapping names, Superuser Group included, in configuration order. */
    public Set<UUID> mappedGroupIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(roleByGroup.keySet()));
    }

    /** Lower-case hex SHA-256 of the canonical rendering; see the class comment. */
    public String hash() {
        return hash;
    }

    private static String digest(
            Map<String, Role> roles, Map<UUID, Role> roleByGroup, UUID superuserGroupId) {
        StringBuilder canonical = new StringBuilder();
        roles.values().stream()
                .sorted(Comparator.comparing(Role::name))
                .forEach(role -> canonical.append("role\t").append(role.name()).append('\t')
                        .append(role.permissions().stream()
                                .sorted(Permission.BY_VALUE)
                                .map(Permission::value)
                                .collect(Collectors.joining(",")))
                        .append('\n'));
        roleByGroup.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append("group\t").append(entry.getKey()).append('\t')
                        .append(entry.getValue().name()).append('\t')
                        .append(entry.getKey().equals(superuserGroupId) ? "superuser" : "-")
                        .append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException absent) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(absent);
        }
    }

    /**
     * One Role as configuration states it: Permission NAMES, so an unknown one can be reported
     * rather than lost in binding.
     */
    public record RoleDefinition(String name, List<String> permissions) {
    }

    /** One mapping entry: the Group's stable id, the Role it confers, and the Superuser marker. */
    public record GroupAssignment(UUID groupId, String role, boolean superuser) {
    }
}
