package com.example.backend.scim.domain;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.StoredGroupPermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The stored Permissions of the directory's Groups, as the startup step reads and seeds them and the
 * Superuser replaces them (ADR 0015). Sign-in, the Groups projection and the audit trail read them
 * through {@link com.example.backend.authorization.domain.GroupPermissions} instead.
 *
 * <p>No SCIM path reaches this port: a connector manages a Group's existence and membership, never
 * what it confers. A Group's rows go with the Group when it is deleted.
 *
 * <p>The port owns the rule of what a Group may carry, so no writer can put a Permission where
 * ADR 0015 forbids it: it gives a Group only {@linkplain Permission#delegatable() delegatable}
 * Permissions, gives nothing to a Group that does not exist, and gives nothing at all to the Admin
 * group, whose direct members are the Superuser. {@link #add} and {@link #replace} refuse a write
 * that breaks it, writing nothing, with the first {@link GroupPermissionRefusal} it is, in that
 * order. A writer that reports a refusal of its own asks {@link #refusal} first and reports what it
 * answers; the writes refuse regardless, for a writer that did not ask.
 */
public interface ScimGroupPermissionStore {

    /** Every stored row, by name, so startup can refuse one the vocabulary does not have. */
    List<StoredGroupPermission> findAll();

    /** Whether this Group carries any Permission at all. */
    boolean carriesAny(UUID groupId);

    /**
     * What {@link #add} or {@link #replace} would refuse of giving this Group these Permissions, or
     * empty when either would write. Writes nothing. Joins the caller's transaction, so what it
     * answers holds for a write made under the same lock.
     */
    Optional<GroupPermissionRefusal> refusal(UUID groupId, Set<Permission> permissions);

    /**
     * Gives this existing Group these Permissions, beside whatever it carries already.
     *
     * @throws GroupPermissionsRefusedException when {@link #refusal} has a refusal for them, having
     *                                          written nothing
     */
    void add(UUID groupId, Set<Permission> permissions);

    /**
     * Makes this existing Group carry exactly these Permissions, and nothing it carried before
     * besides: the Superuser's one write of a Group's Permissions. An empty set clears it. Joins
     * the caller's transaction, whose resource lock on the Group it relies on to keep two
     * replacements from interleaving.
     *
     * @throws GroupPermissionsRefusedException when {@link #refusal} has a refusal for them, having
     *                                          written nothing, the Group keeping what it carried
     */
    void replace(UUID groupId, Set<Permission> permissions);
}
