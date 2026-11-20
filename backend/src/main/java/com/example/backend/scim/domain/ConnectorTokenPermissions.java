package com.example.backend.scim.domain;

import com.example.backend.authorization.domain.Permission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a connector token may do: a set of Permissions from the shared vocabulary (ADR 0015).
 *
 * <p>The same names the session's vocabulary spells, so "this connector manages Groups but not
 * Users" is {@code group:read} and {@code group:write} and nothing else. A token can carry only
 * the {@linkplain Permission#tokenGrantable() token-grantable} Permissions: everything else in
 * the vocabulary guards the application chain, which no bearer token reaches, so granting it
 * would mean nothing today and a back door the day a route started honouring it. A token with a
 * write Permission is Superuser-equivalent, which is why only the Superuser mints one; there is
 * no further rule on what a minter may grant.
 *
 * <p>A token carries at least one Permission, however its set is built: a token being issued or
 * rotated and a stored token alike.
 *
 * @param values the Permissions, at least one, every one of them
 *               {@linkplain Permission#tokenGrantable() token-grantable}
 */
public record ConnectorTokenPermissions(Set<Permission> values) {

    public ConnectorTokenPermissions {
        if (values == null || values.isEmpty()) {
            throw new InvalidConnectorTokenPermissionsException(
                    "A token must carry at least one Permission");
        }
        values = Set.copyOf(values);
        if (!values.stream().allMatch(Permission::tokenGrantable)) {
            throw new InvalidConnectorTokenPermissionsException(
                    "A token can carry only user:read, user:write, group:read and group:write");
        }
    }

    /**
     * The Permissions a token is being issued with, refused when there are none or when any of
     * them is not {@linkplain Permission#tokenGrantable() token-grantable}.
     */
    public static ConnectorTokenPermissions requested(Collection<Permission> requested) {
        return new ConnectorTokenPermissions(requested == null ? null : Set.copyOf(requested));
    }

    /** Whether this token holds {@code permission}. */
    public boolean permits(Permission permission) {
        return values.contains(permission);
    }

    /** Whether this token may read at least one resource type. */
    public boolean permitsAnyRead() {
        return values.contains(Permission.USER_READ) || values.contains(Permission.GROUP_READ);
    }

    /** The resource types this token may read, which is what a base {@code /.search} spans. */
    public Set<ScimResourceType> readableTypes() {
        Set<ScimResourceType> readable = EnumSet.noneOf(ScimResourceType.class);
        for (ScimResourceType type : ScimResourceType.values()) {
            if (permits(type.readPermission())) {
                readable.add(type);
            }
        }
        return Set.copyOf(readable);
    }

    /** The Permissions spelled as the wire spells them, sorted by that spelling. */
    public List<String> sortedValues() {
        return Permission.valuesOf(values);
    }
}
