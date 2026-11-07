package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ConnectorLifecycleLocks;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a SCIM write's resource locks are taken, and so the one place their order is
 * decided — see {@link ScimWriteLocks} for the order and the sets.
 *
 * <p>The write path is lock, then store, then apply effects, each in one place: this, the User
 * and Group adapters, and {@code ScimWriteEffectsApplier}. The adapters therefore take no lock of
 * their own, and the bulk version advance {@link ResourceVersionStatements} issues — for the
 * resources {@link com.example.backend.scim.domain.ResourceVersionAdvance} decides — touches only
 * rows already held here, so the order it updates them in cannot matter.
 *
 * <p>Every lock here, the resource locks and the connector lifecycle's alike, is
 * {@code MANDATORY}, as
 * {@code ScimSeedLockAdapter}'s seeding lock is: the row lock is the calling transaction's, and on
 * an auto-commit connection that transaction ends with the statement, so a call outside a
 * transaction would return as if it held the rows and exclude no second writer at all. Refusing
 * that call, with Spring's
 * {@link org.springframework.transaction.IllegalTransactionStateException}, turns a silent loss
 * of every guarantee the callers build on the lock into a failure of the call
 * ({@code ScimWriteLockAdapterIntegrationTests}).
 *
 * <p>Also the one place a connector's and a connector token's row locks are taken
 * ({@link ConnectorLifecycleLocks}), so every lock statement the scim slice issues is issued here.
 * Each is a lock of one row by primary key, issued before the row is read, and the read follows
 * it as the transaction's first of that row: under read-committed snapshot each statement sees
 * what was committed when it began, so a writer that waited here reads what the previous holder
 * committed. A connector lifecycle write takes no resource lock and a SCIM write no connector or
 * token lock, so the lifecycle's order — the connector before its tokens — needs no place in the
 * resource order.
 */
@Repository
class ScimWriteLockAdapter implements ScimWriteLocks, ConnectorLifecycleLocks {

    private final ScimResourceJpaRepository resources;
    private final ScimGroupMemberJpaRepository memberships;
    private final ScimConnectorJpaRepository connectorRows;
    private final ScimConnectorTokenJpaRepository tokenRows;
    private final ScimConnectorRepository connectors;
    private final ScimConnectorTokenRepository tokens;

    ScimWriteLockAdapter(
            ScimResourceJpaRepository resources,
            ScimGroupMemberJpaRepository memberships,
            ScimConnectorJpaRepository connectorRows,
            ScimConnectorTokenJpaRepository tokenRows,
            ScimConnectorRepository connectors,
            ScimConnectorTokenRepository tokens) {
        this.resources = resources;
        this.memberships = memberships;
        this.connectorRows = connectorRows;
        this.tokenRows = tokenRows;
        this.connectors = connectors;
        this.tokens = tokens;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ScimConnector> lockLiveConnector(UUID connectorId) {
        connectorRows.lockById(connectorId);
        return connectors.findById(connectorId).filter(connector -> !connector.isDeleted());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ScimConnectorToken> lockTokenOf(ScimConnector connector, UUID tokenId) {
        tokenRows.lockById(tokenId);
        return tokens.findById(tokenId)
                .filter(token -> token.connectorId().equals(connector.id()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUserWrite(UUID userId) {
        lockWithRelated(userId, List.of(), () -> memberships.findGroupIdsOfUser(userId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockGroupWrite(UUID groupId, Collection<UUID> namedUsers) {
        lockWithRelated(groupId, namedUsers, () -> memberships.findMemberIds(groupId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
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
     * One row per statement, in {@link ScimWriteLocks#ORDER}: each statement names one primary
     * key, so no engine has a second row to reorder it against, and the order of the statements
     * is the order of the locks. Nothing to lock, so no statement: the second batch of
     * {@link #lockWithRelated} is empty on almost every write.
     *
     * <p>The cost is a round trip per row, which a Group with many members makes visible — a
     * rename of a Group of two hundred locks two hundred and one rows. A batched statement would
     * save those trips only where the engine locks in sort order, and splitting the set into
     * ordered chunks would not help either: inside each chunk the order is the engine's again, and
     * two writers whose sets overlap in one chunk deadlock as if there were no chunks. The trips
     * are each an index lookup on a row nobody else holds, against a write that already reads the
     * Group's membership and advances every member's version in the same transaction.
     */
    private void lockInOrder(Collection<UUID> ids) {
        ids.stream().sorted(ScimWriteLocks.ORDER).forEach(resources::lockResource);
    }
}
