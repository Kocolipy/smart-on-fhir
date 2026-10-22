package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.AuthorizationChangeCounts;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimAttributeLimits;
import com.example.backend.scim.domain.ScimExternalIdRepository;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, reading, changing and deleting SCIM Groups, as a connector asks for it.
 *
 * <p>Every method takes the {@link AuthenticatedConnector} rather than reading it from a
 * security context, for the two reasons {@link ScimUserService} does: an {@code externalId} is
 * connector-scoped, so a read that did not know who was asking could not resolve the alias,
 * and an audit event names the acting connector, so making the actor a parameter means no code
 * path exists that records a SCIM operation with no actor.
 *
 * <h2>What a Group write may not do</h2>
 *
 * <p>Two resources are reserved for the deployment's recovery, and the refusals below are the
 * whole of their protection on this surface:
 *
 * <ul>
 *   <li>The <strong>Admin group</strong> — whose direct members are the Superuser — may not be
 *       renamed and may not be deleted. Its ordinary membership stays writable, because granting
 *       and revoking access is exactly what external provisioning is for. Every other Group is an
 *       ordinary Group on this surface, whatever it confers: renamable, deletable, its membership
 *       writable.
 *   <li>The <strong>Bootstrap Admin's</strong> membership is frozen — it cannot be removed
 *       from the Admin group, and it cannot be added to or removed from any other Group.
 *       The glossary's rule is that no SCIM operation may mutate that User, and a membership
 *       change does mutate it: it moves the User's computed {@code groups} attribute and
 *       advances its version, which a client reads.
 * </ul>
 *
 * <p>The calling connector's own {@code externalId} on the Admin group stays writable. It is that
 * connector's name for the Group, not part of the Group, and no other connector reads it, so
 * changing it neither renames the recovery authority nor alters who holds it — and an IdP that
 * re-keys its objects must be able to keep finding the Group it manages.
 *
 * <p>Every refusal is raised before anything is written, so a refused write changes nothing —
 * which is what makes "verified by re-reading unchanged" a property of this class rather than
 * of a test's luck. Each is audited, but not here: a refusal is audited by what it is, once, at
 * the edge of the request, whichever module raised it, so this class raises and never catches
 * to record. The append there is deliberately the fail-open kind: the refusal rolls this
 * transaction back, so an append that joined it would be rolled back with it, and an attempt to
 * provision the recovery authority away would leave no trace.
 *
 * <h2>A membership of a Group that confers Permissions is a change of power</h2>
 *
 * <p>A Group that confers Permissions — or the Admin group, which confers the Superuser — confers
 * them on every direct member, so writing its membership is granting them, which is why
 * {@code group:write} on such a Group is as powerful as what it confers. Every write that moves
 * such a Group's membership is therefore also recorded as a change of power: a
 * {@code PERMISSION_GRANT} for each User it added and a {@code PERMISSION_REVOKE} for each User it
 * removed, naming the User, the Group and the User's effective Permissions before and after, and
 * a matching {@code INFO} record in the application log. A grant of the Superuser is counted too.
 *
 * <p>A session carries the Permissions it was issued with, so a User removed from such a Group
 * would otherwise keep them for the rest of its lifetime. Every write that drops a direct member
 * of a Group that confers Permissions — a PATCH {@code remove}, a PATCH {@code replace} of the
 * members, a PUT whose member list leaves the User out, or the Group's DELETE — therefore ends that
 * User's sessions through {@link ScimUserSessions}, which does so only once the write commits. A
 * refused, stale or rolled-back write revokes nothing; neither does a removal from a Group that
 * confers nothing, nor an ADDITION, whose Permissions the User holds from its next sign-in.
 *
 * <p>Which Users gain or lose Permissions, and whose Sessions end, is decided by
 * {@link ScimWriteEffects} from the membership before and after, and carried out by the apply
 * step every SCIM write shares ({@link ScimWriteEffectsApplier}), which also advances the version
 * of a write that moved only the calling connector's alias.
 */
@Service
public class ScimGroupService {

    private final ScimGroupRepository groups;
    private final ScimUserRepository users;
    private final ScimExternalIdRepository aliases;
    private final ScimTombstoneRepository tombstones;
    private final AuditTrail audit;
    private final Clock clock;
    private final ScimQueryRepository queries;
    private final GroupPermissions permissions;
    private final ScimWriteEffectsApplier effects;
    private final ScimWriteLocks writeLocks;

    public ScimGroupService(
            ScimGroupRepository groups,
            ScimWriteLocks writeLocks,
            ScimUserRepository users,
            ScimExternalIdRepository aliases,
            ScimUserSessions sessions,
            ScimTombstoneRepository tombstones,
            AuditTrail audit,
            Clock clock,
            ScimQueryRepository queries,
            GroupPermissions permissions,
            AuthorizationChangeCounts counts) {
        this.queries = queries;
        this.groups = groups;
        this.writeLocks = writeLocks;
        this.users = users;
        this.aliases = aliases;
        this.tombstones = tombstones;
        this.audit = audit;
        this.clock = clock;
        this.permissions = permissions;
        this.effects = new ScimWriteEffectsApplier(
                users, groups, sessions, audit, permissions, counts);
    }

    /**
     * Creates a Group, its connector alias and its audit event in one transaction.
     *
     * <p>A {@code displayName} or {@code externalId} its column cannot hold — too long, or with a
     * forbidden control character — is refused first, as {@code invalidValue} naming the
     * attribute.
     *
     * <p>The members are locked first, through the lock module and in its order: each one's
     * version advances, and the new Group itself is visible to no other writer yet.
     *
     * <p>A {@code displayName} already taken arrives as {@link DuplicateDisplayNameException}
     * from the failed statement rather than from a prior read, and a member that is not a live
     * User as {@link UnknownGroupMemberException} from the failed membership insert. Both
     * propagate for the adapter to render, and are audited at the edge in a transaction of their
     * own — this one is already doomed.
     */
    @Transactional
    @RetriedOnDeadlock(Operation.SCIM_WRITE)
    public ScimGroupResource create(AuthenticatedConnector connector, NewScimGroup command) {
        ScimAttributeLimits.requireGroupDisplayNameWithin(command.displayName());
        ScimAttributeLimits.requireExternalIdWithin(command.externalId());
        writeLocks.lock(command.memberIds());
        refuseFrozenMembershipChange(command.memberIds());
        ScimGroup group = ScimGroup.created(
                UUID.randomUUID(),
                command.displayName(),
                references(command.memberIds()),
                clock.instant());
        ScimGroup created = groups.create(group);
        if (command.externalId() != null) {
            aliases.put(connector.connectorId(), created.id(), command.externalId());
        }
        audit.append(new Audited.ScimGroupCreated(connector.connectorId(), created.id()));
        return ScimGroupResource.of(created, command.externalId());
    }

    /**
     * One Group by its stable id, as this connector sees it.
     *
     * <p>Deliberately not audited, for the reason a single User read is not: a single-resource
     * retrieval is the ordinary unit of provisioning traffic, and recording it would bury the
     * collection reads that indicate an enumeration. The absence of a call here is where that
     * distinction is enforced.
     */
    @Transactional(readOnly = true)
    public Optional<ScimGroupResource> findById(AuthenticatedConnector connector, UUID id) {
        return groups.findById(id).map(group -> projection(connector, group));
    }

    /**
     * A query of the Group collection — {@code GET /Groups} or {@code POST /Groups/.search} —
     * and the total it matched.
     *
     * <p>Audited as exactly one bulk read before the page is returned, on the terms the User
     * query is: whatever the page size and whatever comes back, with the number of Groups
     * returned and the filter's shape, inside the transaction that reads.
     */
    @Transactional
    public ScimGroupListing query(AuthenticatedConnector connector, ScimQuery query, String baseUri) {
        ScimQuery.Result result = queries.query(query, connector.connectorId(), baseUri);
        List<ScimGroupResource> resources =
                resources(connector, result.idsOf(ScimResourceType.GROUP));
        audit.append(new Audited.ScimGroupsQueried(
                connector.connectorId(), resources.size(), ScimAuditFilterShapes.of(query.filter())));
        return new ScimGroupListing(resources, result.totalResults(), query.page());
    }

    /**
     * These Groups as this connector sees them, in the order given; an id naming no live Group
     * is skipped. Not audited: the caller is a query that audits itself.
     */
    List<ScimGroupResource> resources(AuthenticatedConnector connector, List<UUID> ids) {
        return groups.findAllById(ids).stream().map(group -> projection(connector, group)).toList();
    }

    /**
     * Replaces a Group's label and its whole membership — a PUT.
     *
     * <p>An attribute the submitted document did not mention has already become its empty value
     * at the adapter, because that is what replacing a resource means. So a PUT with no
     * {@code members} empties the Group, and the refusals below see that as the removal it is.
     */
    @Transactional
    @RetriedOnDeadlock(Operation.SCIM_WRITE)
    public Optional<ScimGroupResource> replace(
            AuthenticatedConnector connector,
            UUID id,
            ScimVersionPrecondition precondition,
            ScimGroupReplacement replacement) {
        return apply(
                connector,
                id,
                precondition,
                replacement.memberIds(),
                current -> new GroupEdit(
                        current.group().replacedWith(
                                replacement.displayName(),
                                references(replacement.memberIds()),
                                clock.instant()),
                        replacement.externalId()));
    }

    /**
     * Applies a sequence of PATCH operations to a Group.
     *
     * <p><strong>Partial failure rolls back entirely.</strong> The operations are folded onto
     * the Group in memory and written once, so a sequence whose third operation is refused
     * leaves the first two unapplied — there is no intermediate state to roll back, because
     * none was ever written. That is stronger than a transactional rollback and is the reason
     * the fold happens here rather than one write per operation.
     *
     * <p>The refusals are evaluated against the RESULT of the whole sequence, not against each
     * step: a PATCH that removes the Bootstrap Admin and adds it back is not a violation,
     * because the User it must not mutate ends up as it started.
     */
    @Transactional
    @RetriedOnDeadlock(Operation.SCIM_WRITE)
    public Optional<ScimGroupResource> patch(
            AuthenticatedConnector connector,
            UUID id,
            ScimVersionPrecondition precondition,
            List<ScimGroupPatchOperation> operations) {
        return apply(connector, id, precondition, namedUsers(operations),
                current -> folded(current, operations));
    }

    /**
     * The Group the operations describe, computed without writing anything.
     *
     * <p>A method rather than the body of a lambda because the fold reassigns what it is
     * building, which a lambda's captured locals cannot do — and because naming it says what it
     * is: the whole PATCH resolved to a single desired state, which is what makes partial
     * failure unrepresentable.
     */
    private GroupEdit folded(GroupEdit current, List<ScimGroupPatchOperation> operations) {
        String displayName = current.group().displayName();
        String externalId = current.externalId();
        List<UUID> memberIds = new ArrayList<>(
                current.group().members().stream().map(ScimGroupMember::userId).toList());
        for (ScimGroupPatchOperation operation : operations) {
            switch (operation) {
                case ScimGroupPatchOperation.SetDisplayName set -> displayName = set.displayName();
                case ScimGroupPatchOperation.SetExternalId set -> externalId = set.externalId();
                case ScimGroupPatchOperation.RemoveExternalId ignored -> externalId = null;
                case ScimGroupPatchOperation.AddMembers add -> memberIds.addAll(add.userIds());
                case ScimGroupPatchOperation.RemoveMembers remove ->
                        memberIds.removeAll(remove.userIds());
                case ScimGroupPatchOperation.ReplaceMembers replace -> {
                    memberIds.clear();
                    memberIds.addAll(replace.userIds());
                }
                case ScimGroupPatchOperation.RemoveAllMembers ignored -> memberIds.clear();
            }
        }
        return new GroupEdit(
                current.group().replacedWith(displayName, references(memberIds), clock.instant()),
                externalId);
    }

    /** Every User a PATCH's operations name; see {@link ScimGroupPatchOperation#namedUserIds}. */
    private static Set<UUID> namedUsers(List<ScimGroupPatchOperation> operations) {
        Set<UUID> named = new LinkedHashSet<>();
        operations.forEach(operation -> named.addAll(operation.namedUserIds()));
        return named;
    }

    /**
     * Deletes a Group.
     *
     * <p>The Admin group cannot be deleted, so the refusal is checked before anything is
     * removed. An ordinary Group containing the Bootstrap Admin needs no special case: such a
     * Group cannot exist, because no write that would have added the User to it is accepted.
     *
     * <p>Any other Group can be: deleting one that confers Permissions removes them from every
     * member, so each member is recorded as losing them and has its sessions ended after the
     * commit, exactly as removing them one by one would — a shrink to empty (ADR 0002, ADR 0015).
     * The deletion takes the Group's stored Permissions with it, so what it carried is read
     * before the delete and recorded from that.
     *
     * <p>The Group and every member are locked first, through the lock module and in its order:
     * each member's version advances.
     *
     * <p>A tombstone holding only the id, the type and the time is written in the same
     * transaction; it is never consulted for uniqueness, so the former {@code displayName} is
     * free for the next create.
     *
     * <p>Reports whether a Group was there to delete, so the adapter renders absence as a
     * {@code 404} rather than this throwing an exception the caller cannot distinguish from a
     * failure.
     */
    @Transactional
    @RetriedOnDeadlock(Operation.SCIM_WRITE)
    public boolean delete(
            AuthenticatedConnector connector, UUID id, ScimVersionPrecondition precondition) {
        Optional<ScimGroup> stored = lockedForWrite(id, List.of());
        if (stored.isEmpty()) {
            return false;
        }
        ScimGroup group = stored.get();
        precondition.requireSatisfiedBy(group.version());
        if (group.isProtectedFromWrites()) {
            throw new ProtectedResourceException(group.reservedName());
        }
        Instant now = clock.instant();
        // Read before the delete, whose cascade takes the rows. The Admin group, the one Group
        // that confers power without carrying any, was refused above, so what this Group
        // carries is the whole of what its membership confers.
        Set<Permission> carried = permissions.permissionsOf(List.of(id));
        boolean confersPermissions = !carried.isEmpty();
        boolean deleted = groups.deleteById(id, now);
        if (deleted) {
            // Only for a row this transaction removed: a Group deleted by another between the
            // read and the delete already has its tombstone, and a second would collide on it.
            tombstones.record(ScimResourceType.GROUP, id, now);
        }
        audit.append(new Audited.ScimGroupDeleted(connector.connectorId(), id));
        if (deleted) {
            effects.afterDeleting(id, carried).apply(
                    ScimWriteEffectsApplier.Writer.connector(connector.connectorId()),
                    ScimWriteEffects.ofGroupDeletion(id, memberIdSet(group), confersPermissions),
                    now);
        }
        return deleted;
    }

    /**
     * The Group a write or deletion acts on, read only once the lock module holds it, its current
     * members and every User the request names, so the read is the committed state and the
     * version it carries is the one {@code If-Match} is checked against. The one way into a Group
     * write: the lock must come before the read, and naming the pair keeps the two from being
     * separated.
     */
    private Optional<ScimGroup> lockedForWrite(UUID id, Collection<UUID> namedUsers) {
        writeLocks.lockGroupWrite(id, namedUsers);
        return groups.findById(id);
    }

    /**
     * The shared shape of a Group write: read the stored Group, compute what it should become,
     * refuse what the reservations forbid, write it, audit what moved.
     *
     * <p>The Group is read only once the lock module holds it, its current members and every User
     * the request names — a superset of every resource the write can advance, in the module's one
     * order — and the {@code If-Match} precondition, when one was sent, checked against that
     * version, so two writers holding the same precondition produce one success and one
     * {@code 412} rather than a lost update. A write without one is applied unconditionally under
     * the same lock, last writer wins.
     *
     * <p>One method for PUT and PATCH because everything except the computation is identical,
     * and because the refusals must not be able to differ between the two verbs — a protection
     * that held for PUT and not for PATCH would be no protection at all.
     */
    private Optional<ScimGroupResource> apply(
            AuthenticatedConnector connector,
            UUID id,
            ScimVersionPrecondition precondition,
            Collection<UUID> namedUsers,
            UnaryOperator<GroupEdit> change) {
        Optional<ScimGroup> stored = lockedForWrite(id, namedUsers);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScimGroup current = stored.get();
        // After existence, before anything is computed: a malformed or stale precondition changes
        // nothing, and the lock above is what makes "stale" exact under concurrent writers.
        precondition.requireSatisfiedBy(current.version());
        UUID connectorId = connector.connectorId();
        String currentAlias = aliases.find(connectorId, id).orElse(null);
        GroupEdit edit = change.apply(new GroupEdit(current, currentAlias));
        ScimGroup desired = edit.group();
        ScimAttributeLimits.requireGroupDisplayNameWithin(desired.displayName());
        ScimAttributeLimits.requireExternalIdWithin(edit.externalId());

        if (current.isProtectedFromWrites()
                && !current.displayName().equals(desired.displayName())) {
            throw new ProtectedResourceException(current.reservedName());
        }
        refuseFrozenMembershipChange(current, desired);

        // A taken displayName or a member naming no live User fails here, audited at the edge.
        ScimGroup written = groups.replace(desired).orElse(null);
        if (written == null) {
            // Deleted between the read and the write. The caller sees the same 404 it would
            // have seen a moment earlier, which is the truthful answer either way.
            return Optional.empty();
        }
        ScimWriteEffects<AuditGroupAttribute> implied = ScimWriteEffects.ofGroupWrite(id,
                new ScimWriteEffects.GroupState(
                        current.displayName(), memberIdSet(current), currentAlias),
                new ScimWriteEffects.GroupState(
                        written.displayName(), memberIdSet(written), edit.externalId()),
                permissions.confersPermissions(id));
        if (implied.stored().alias()) {
            writeAlias(connectorId, id, edit.externalId());
        }
        audit.append(new Audited.ScimGroupReplaced(connectorId, id, implied.audited()));
        // The alias is not a Group column, so a replacement that moved only the alias advanced
        // nothing; the apply step advances the version the connector reads.
        effects.apply(ScimWriteEffectsApplier.Writer.connector(connectorId), implied,
                desired.lastModifiedAt());
        // Read back rather than taken from the replacement: the version may have advanced above.
        return groups.findById(id).map(group -> projection(connector, group));
    }

    /**
     * Refuses a write that would add the Bootstrap Admin to a Group or remove it from one.
     *
     * <p>Evaluated on the resulting membership rather than on the operations, so a sequence
     * that removes and re-adds the User is allowed: the rule protects the User's state, not the
     * shape of the request.
     */
    private void refuseFrozenMembershipChange(ScimGroup current, ScimGroup desired) {
        Optional<UUID> bootstrapAdminId = bootstrapAdminId();
        if (bootstrapAdminId.isEmpty()) {
            return;
        }
        UUID protectedUserId = bootstrapAdminId.get();
        if (current.hasMember(protectedUserId) != desired.hasMember(protectedUserId)) {
            throw new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN);
        }
    }

    /**
     * The create-time form of the same rule: a new Group starts with no members, so any
     * membership naming the Bootstrap Admin is adding it.
     *
     * <p>Audited as a refused CREATE rather than a refused write, because there is no resource
     * to name — a Group that was never created has no id, so {@code Audited.ScimGroupCreateRejected}
     * records the connector and the reason and nothing else. The edge records it, from the route
     * the refusal was raised on, as it records every refusal: an earlier version audited each
     * refusal where it was raised, and this one was once missed — an attempt to provision the
     * recovery authority away left no trace — which is the gap auditing at the edge closes.
     */
    private void refuseFrozenMembershipChange(List<UUID> memberIds) {
        Optional<UUID> bootstrapAdminId = bootstrapAdminId();
        if (bootstrapAdminId.isEmpty()) {
            return;
        }
        if (memberIds.contains(bootstrapAdminId.get())) {
            throw new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN);
        }
    }

    /**
     * The Bootstrap Admin's id, or empty before seeding has run.
     *
     * <p>Empty is not an error and does not open the protection: with no Bootstrap Admin there
     * is no User whose membership could be frozen, and every Group write is therefore
     * unaffected. It happens exactly once per deployment, between the first migration and the
     * first startup's seeding.
     */
    private Optional<UUID> bootstrapAdminId() {
        return users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).map(ScimUser::id);
    }

    private static Set<UUID> memberIdSet(ScimGroup group) {
        return group.members().stream()
                .map(ScimGroupMember::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Sets or removes the calling connector's alias. Keyed by the calling connector alone, so no
     * write of one connector reaches another's alias for the same Group.
     */
    private void writeAlias(UUID connectorId, UUID id, String externalId) {
        if (externalId == null) {
            aliases.remove(connectorId, id);
        } else {
            aliases.put(connectorId, id, externalId);
        }
    }

    /**
     * A Group write's desired state: the Group, and the calling connector's alias for it — which
     * is not a Group column, so it travels beside the Group rather than inside it.
     */
    private record GroupEdit(ScimGroup group, String externalId) {
    }

    /** Submitted ids as membership values, with no label — the label is read-only. */
    private static List<ScimGroupMember> references(List<UUID> userIds) {
        return userIds.stream().map(ScimGroupMember::reference).toList();
    }

    /** The stored Group as this connector sees it, alias included. */
    private ScimGroupResource projection(AuthenticatedConnector connector, ScimGroup group) {
        return ScimGroupResource.of(
                group, aliases.find(connector.connectorId(), group.id()).orElse(null));
    }
}
