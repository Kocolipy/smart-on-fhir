package com.example.backend.scim.domain;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.StoredGroupPermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The stored Permissions of the directory's Groups, as the startup step reads and seeds them and the
 * Superuser replaces them (ADR 0015). Sign-in, the Groups projection and the audit trail read them
 * through {@link com.example.backend.authorization.domain.GroupPermissions} instead.
 *
 * <p>No SCIM path reaches this port: a connector manages a Group's existence and membership, never
 * what it confers. A Group's rows go with the Group when it is deleted.
 */
public interface ScimGroupPermissionStore {

    /** Every stored row, by name, so startup can refuse one the vocabulary does not have. */
    List<StoredGroupPermission> findAll();

    /** Whether this Group carries any Permission at all. */
    boolean carriesAny(UUID groupId);

    /** Gives this existing Group these Permissions, beside whatever it carries already. */
    void add(UUID groupId, Set<Permission> permissions);

    /**
     * Makes this existing Group carry exactly these Permissions, and nothing it carried before
     * besides: the Superuser's one write of a Group's Permissions. An empty set clears it. Joins
     * the caller's transaction, whose resource lock on the Group it relies on to keep two
     * replacements from interleaving.
     */
    void replace(UUID groupId, Set<Permission> permissions);
}
