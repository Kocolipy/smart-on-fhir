package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ConnectorTokenLocks;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;

/**
 * The one place a SCIM write's resource locks are taken, and so the one place their order is
 * decided — see {@link ScimWriteLocks} for the order and the sets.
 *
 * <p>The write path is lock, then store, then apply effects, each in one place: this, the User
 * and Group adapters, and {@code ScimWriteEffectsApplier}. The adapters therefore take no lock of
 * their own, and the bulk version advance {@link RepresentationChange} issues touches only rows
 * already held here, so the order it updates them in cannot matter.
 *
 * <p>Also the one place a connector token's row lock is taken ({@link ConnectorTokenLocks}), so
 * every lock statement the scim slice issues is issued here. A token write takes no resource lock
 * and a SCIM write no token lock, so the token lock needs no place in the resource order.
 */
@Repository
class ScimWriteLockAdapter implements ScimWriteLocks, ConnectorTokenLocks {

    private final ScimResourceJpaRepository resources;
    private final ScimGroupMemberJpaRepository memberships;
    private final ScimConnectorTokenJpaRepository tokens;

    ScimWriteLockAdapter(
            ScimResourceJpaRepository resources,
            ScimGroupMemberJpaRepository memberships,
            ScimConnectorTokenJpaRepository tokens) {
        this.resources = resources;
        this.memberships = memberships;
        this.tokens = tokens;
    }

    @Override
    public void lockToken(UUID tokenId) {
        tokens.lockById(tokenId);
    }

    @Override
    public void lockUserWrite(UUID userId) {
        lockWithRelated(userId, List.of(), () -> memberships.findGroupIdsOfUser(userId));
    }

    @Override
    public void lockGroupWrite(UUID groupId, Collection<UUID> namedUsers) {
        lockWithRelated(groupId, namedUsers, () -> memberships.findMemberIds(groupId));
    }

    @Override
    public void lock(Collection<UUID> resourceIds) {
        lockInOrder(resourceIds);
    }

    /**
     * The subject, the named resources and the related ones read without a lock, in one ordered
     * batch; then whatever joined the related set before the subject's lock was granted.
     */
    private void lockWithRelated(
            UUID subject, Collection<UUID> named, Supplier<List<UUID>> related) {
        Set<UUID> locked = new LinkedHashSet<>();
        locked.add(subject);
        locked.addAll(named);
        locked.addAll(related.get());
        lockInOrder(locked);
        Set<UUID> joined = new LinkedHashSet<>(related.get());
        joined.removeAll(locked);
        lockInOrder(joined);
    }

    /**
     * Nothing to lock, so no statement: the second batch of {@link #lockWithRelated} is empty on
     * almost every write, and skipping it saves a round trip per write. An empty batch would lock
     * nothing either way.
     */
    private void lockInOrder(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            resources.lockInIdOrder(ids);
        }
    }
}
