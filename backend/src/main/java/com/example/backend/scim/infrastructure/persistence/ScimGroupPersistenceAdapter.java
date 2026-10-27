package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ResourceVersionAdvance;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupHeadcount;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import com.example.backend.scim.infrastructure.persistence.entity.ScimGroupEntity;
import com.example.backend.scim.infrastructure.persistence.entity.ScimGroupMemberEntity;
import com.example.backend.scim.infrastructure.persistence.entity.ScimResourceEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Maps the SCIM Group port onto the normalized JPA model. Each write describes itself to
 * {@link ResourceVersionAdvance}, which decides whose versions the port's promise advances, and
 * hands the answer to {@link ResourceVersionStatements}, which advances them.
 *
 * <p>Stores only. Every row a write here touches — the Group, its members before and after, the
 * Users whose versions advance — is locked before the write reaches this adapter, in one order,
 * by {@link ScimWriteLockAdapter}; so this adapter takes no lock and decides no order.
 *
 * <h2>Why every write flushes twice</h2>
 *
 * <p>A Group write can violate two different constraints — the unique
 * {@code displayName}, and the membership's foreign key to the User table — and the two
 * must be reported differently: one is a {@code 409 uniqueness}, the other a
 * {@code 400 invalidValue} about a member that is not a live User. The name and the membership
 * are written in two flushes, so each violation is attributable to the statement that caused it.
 * The name's flush is attributed further, by constraint name: it can also fail on a value too
 * long for its column, which is not a conflict, so only {@code uq_scim_groups_normalized_display_name}
 * becomes the {@code 409} — see {@link IntegrityViolations}.
 *
 * <h2>Deletion goes through the resource row</h2>
 *
 * <p>A Group is deleted by deleting its {@code scim_resources} row, not its
 * {@code scim_groups} row. The foreign key runs that way — {@code scim_groups.resource_id}
 * references the resource with {@code ON DELETE CASCADE} — so deleting the Group row alone
 * would leave the resource row behind, holding the id and keeping it from ever being
 * reissued.
 */
@Repository
class ScimGroupPersistenceAdapter implements ScimGroupRepository {

    private static final Sort BY_NORMALIZED_DISPLAY_NAME = Sort.by("normalizedDisplayName");

    private final ScimGroupJpaRepository groups;
    private final ScimGroupMemberJpaRepository memberships;
    private final ScimResourceJpaRepository resources;

    ScimGroupPersistenceAdapter(
            ScimGroupJpaRepository groups,
            ScimGroupMemberJpaRepository memberships,
            ScimResourceJpaRepository resources) {
        this.groups = groups;
        this.memberships = memberships;
        this.resources = resources;
    }

    /**
     * Writes the resource row, the Group row and the memberships, and advances the version of
     * every User the new Group names.
     *
     * <p>That last part is easy to miss, and was missed: a create with members IS a membership
     * change for those Users — each one's rendered {@code groups} gains an entry. It is now
     * {@link ResourceVersionAdvance#groupCreated}'s decision rather than this method's, so it
     * cannot be forgotten here again.
     *
     * <p>The reservation is a literal {@code null}: a Group that arrives through the
     * provisioning path cannot claim the Admin group's authority, and that is a property of
     * this adapter rather than of whichever use case calls it. Seeding writes its reserved
     * Group through {@link #createReserved}.
     */
    @Override
    public ScimGroup create(ScimGroup group) {
        return insert(group, null);
    }

    /**
     * Writes the Group the deployment reserves — the Admin group.
     *
     * <p>A violation here has one more possible cause than in {@link #create}: the
     * reservation is unique too, so a second attempt to seed the same Admin group violates
     * it. Seeding never expects either: it looks the reservation up first under its lock,
     * because a violation cannot be caught and continued past: SQL Server rolls back only the
     * failed statement, but the exception marks the transaction rollback-only as it leaves the
     * repository, and Hibernate's session may not be flushed after it. A taken
     * {@code displayName} arrives as the duplicate it is; a taken reservation is rethrown as the
     * integrity violation, and either one fails startup.
     */
    @Override
    public ScimGroup createReserved(ScimGroup group, ReservedResourceName reservedName) {
        return insert(group, reservedName);
    }

    @Override
    public Optional<ScimGroup> findById(UUID id) {
        return groups.findById(id).map(entity -> toDomain(entity, membersOf(List.of(id))));
    }

    @Override
    public Optional<ScimGroup> findByReservedName(ReservedResourceName reservedName) {
        return groups.findByResource_ReservedName(reservedName.storedValue())
                .map(entity -> toDomain(
                        entity, membersOf(List.of(entity.getResource().getId()))));
    }

    /**
     * Replaces the display name and the whole membership, then advances the version of
     * every resource the change is visible in.
     *
     * <p>Whose versions move — the membership's symmetric difference, every member on a rename,
     * nothing at all for an identical re-send — is {@link ResourceVersionAdvance#groupReplaced}'s
     * decision, made from the old and new state read here rather than reported by the caller.
     */
    @Override
    public Optional<ScimGroup> replace(ScimGroup group) {
        Optional<ScimGroupEntity> stored = groups.findById(group.id());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        String previousDisplayName = stored.get().getDisplayName();
        List<UUID> previousMemberIds = memberships.findMemberIds(group.id());

        renameOrThrow(group);
        List<UUID> newMemberIds = replaceMemberships(group);

        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupReplaced(
                        group.id(),
                        previousDisplayName,
                        group.displayName(),
                        previousMemberIds,
                        newMemberIds,
                        group.lastModifiedAt()), resources);
        return findById(group.id());
    }

    @Override
    public Optional<ScimGroup> advanceVersion(UUID id, Instant now) {
        // A missing row advances nothing, and findById then reports the absence itself.
        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupTouched(id, now), resources);
        return findById(id);
    }

    /**
     * Deletes the Group and advances the version of every User that was a member.
     *
     * <p>The members' versions are advanced BEFORE the delete, because after it there is no
     * membership row left to read them from. The memberships are then deleted explicitly, before
     * the resource row: the member-side foreign keys take no action (ADR 0013), so the database
     * refuses the Group's deletion while one remains. All of it happens in the caller's
     * transaction, so a failure leaves none of it.
     *
     * <p>The resource row goes in a statement issued here, as the User adapter's does, rather than
     * through {@code deleteById}: that one only schedules the delete for the next flush, and
     * Hibernate flushes inserts before deletes, so a Group read or created later in the same
     * transaction still met the old row — the {@code displayName} and the reservation were not
     * free until commit.
     */
    @Override
    public boolean deleteById(UUID id, Instant now) {
        if (groups.findById(id).isEmpty()) {
            return false;
        }
        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupDeleted(memberships.findMemberIds(id), now), resources);
        memberships.deleteMembershipsOf(id);
        resources.deleteResource(id);
        return true;
    }

    @Override
    public List<ScimGroup> findAllById(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // One membership query for the whole page rather than one per Group: a page of two
        // hundred Groups is otherwise two hundred and one round trips.
        Map<UUID, List<ScimGroupMember>> byGroup = membersOf(ids);
        Map<UUID, ScimGroup> byId = new HashMap<>();
        for (ScimGroupEntity entity : groups.findAllById(ids)) {
            byId.put(entity.getResource().getId(), toDomain(entity, byGroup));
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<ScimGroup> findAllOrderedByNormalizedDisplayName() {
        List<ScimGroupEntity> entities = groups.findAll(BY_NORMALIZED_DISPLAY_NAME);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ScimGroupMember>> byGroup = membersOf(entities.stream()
                .map(entity -> entity.getResource().getId())
                .toList());
        return entities.stream().map(entity -> toDomain(entity, byGroup)).toList();
    }

    @Override
    public List<ScimGroupHeadcount> findAllHeadcountsOrderedByNormalizedDisplayName() {
        return groups.findAllHeadcounts().stream()
                .map(row -> new ScimGroupHeadcount(
                        row.id(),
                        row.displayName(),
                        ReservedResourceName.ofStoredValue(row.reservedName()).orElse(null),
                        Math.toIntExact(row.memberCount())))
                .toList();
    }

    @Override
    public boolean existsById(UUID id) {
        return groups.existsById(id);
    }

    @Override
    public List<ScimGroupReference> findGroupsOfUser(UUID userId) {
        return memberships.findGroupsOfUser(userId);
    }

    @Override
    public List<ScimGroupMembership> findDormantMemberships(Instant cutoff) {
        return memberships.findDormantMemberships(cutoff);
    }

    /**
     * Deletes the one membership row and, only when there was one, advances the Group's and the
     * User's versions together — both representations lost an entry.
     */
    @Override
    public boolean removeMember(UUID groupId, UUID userId, Instant now) {
        if (memberships.deleteMembership(groupId, userId) == 0) {
            return false;
        }
        ResourceVersionStatements.advance(
                ResourceVersionAdvance.memberRemoved(groupId, userId, now), resources);
        return true;
    }

    private ScimGroup insert(ScimGroup group, ReservedResourceName reservedName) {
        ScimResourceEntity resource = new ScimResourceEntity(
                group.id(),
                ScimResourceType.GROUP.resourceTypeName(),
                group.version(),
                group.createdAt(),
                group.lastModifiedAt(),
                reservedName == null ? null : reservedName.storedValue());
        try {
            groups.saveAndFlush(new ScimGroupEntity(
                    resource,
                    group.displayName(),
                    group.normalizedDisplayName().value()));
        } catch (DataIntegrityViolationException violation) {
            throw translated(violation);
        }
        List<UUID> memberIds = memberIds(group);
        writeMemberships(group.id(), memberIds);
        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupCreated(memberIds, group.lastModifiedAt()), resources);
        // The row was written a few statements ago in this same transaction, so absence here is an
        // invariant broken, not a case: fail loudly rather than carry a branch no input can reach.
        return findById(group.id()).orElseThrow();
    }

    /** Applies the new display name, reporting the uniqueness violation as its own refusal. */
    private void renameOrThrow(ScimGroup group) {
        try {
            groups.updateDisplayName(
                    group.id(), group.displayName(), group.normalizedDisplayName().value());
        } catch (DataIntegrityViolationException violation) {
            throw translated(violation);
        }
    }

    /**
     * A violation of the live-{@code displayName} constraint as the domain's refusal; any other
     * violation unchanged, for the reason the User adapter gives: only that constraint means the
     * name is taken, and anything else is a fault to be reported as one.
     */
    private static RuntimeException translated(DataIntegrityViolationException violation) {
        return IntegrityViolations.translated(violation,
                IntegrityViolations.DISPLAY_NAME_UNIQUE, DuplicateDisplayNameException::new);
    }

    /** Rewrites the membership wholesale and reports the new set. */
    private List<UUID> replaceMemberships(ScimGroup group) {
        memberships.deleteMembershipsOf(group.id());
        List<UUID> newMemberIds = memberIds(group);
        writeMemberships(group.id(), newMemberIds);
        return newMemberIds;
    }

    /**
     * Writes the membership rows, reporting the foreign-key violation as a member that is
     * not a live User.
     *
     * <p>Flushed inside this call so the violation is attributable to it rather than to the
     * commit, for the reason the User adapter's create flushes: a refusal decided after the
     * response was chosen is a 500, not a 400.
     *
     * <p>An empty membership is a legitimate Group and writes nothing — {@code saveAll} of
     * an empty list would still flush, which is a round trip for no rows.
     */
    private void writeMemberships(UUID groupId, List<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return;
        }
        try {
            memberships.saveAllAndFlush(memberIds.stream()
                    .map(userId -> new ScimGroupMemberEntity(groupId, userId))
                    .toList());
        } catch (DataIntegrityViolationException violation) {
            throw new UnknownGroupMemberException(violation);
        }
    }

    private static List<UUID> memberIds(ScimGroup group) {
        return group.members().stream().map(ScimGroupMember::userId).toList();
    }

    /**
     * The memberships of these Groups, grouped by Group, each member carrying its label.
     *
     * <p>Read in a statement of its own, apart from the Groups', which
     * {@link ScimGroupJpaRepository} reads with their resources, and left so: a deletion
     * committing between the two leaves a Group read with the members it had or with none. That
     * is a stale read rather than a failure, since a Group with no rows here is a legitimate
     * empty Group.
     *
     * <p>One statement per {@link BoundIdChunks chunk} of Group ids: the administrative listing
     * reads every Group in the directory, which a connector may grow past the 2,100 parameters
     * SQL Server binds in one request. Each Group's members all come from its own chunk's
     * statement, so each Group's order is that statement's.
     */
    private Map<UUID, List<ScimGroupMember>> membersOf(Collection<UUID> groupIds) {
        return BoundIdChunks.of(groupIds).stream()
                .flatMap(chunk -> memberships.findMembersOfGroups(chunk).stream())
                .collect(Collectors.groupingBy(
                        ScimGroupMemberRow::groupId,
                        Collectors.mapping(
                                row -> new ScimGroupMember(row.userId(), row.display()),
                                Collectors.toList())));
    }

    private static ScimGroup toDomain(
            ScimGroupEntity entity, Map<UUID, List<ScimGroupMember>> membersByGroup) {
        ScimResourceEntity resource = entity.getResource();
        return new ScimGroup(
                resource.getId(),
                entity.getDisplayName(),
                membersByGroup.getOrDefault(resource.getId(), List.of()),
                ReservedResourceName.ofStoredValue(resource.getReservedName()).orElse(null),
                resource.getVersion(),
                resource.getCreatedAt(),
                resource.getLastModifiedAt());
    }
}
