package com.example.backend.scim;

import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SCIM Group store for tests, standing in for the JPA adapter.
 *
 * <p>Holds a reference to the User store, and that is not a convenience: the real schema makes
 * "a member must be a live User" a foreign key, so a fake that accepted any id would let a test
 * pass while the production write failed. Every membership written here is checked against the
 * User store and refused the same way.
 *
 * <p>The version bumps the port PROMISES are performed here too, for the same reason — they are
 * the port's contract, not the adapter's private business, and a fake that skipped them would
 * hide a User left behind a stale ETag. A membership change advances the Group and the Users
 * added or removed; a rename advances the Group and every current member.
 */
public final class InMemoryScimGroupRepository implements ScimGroupRepository {

    private final Map<UUID, ScimGroup> stored = new LinkedHashMap<>();

    /** Groups whose next delete must report that it removed nothing. See vanishBeforeNextDelete. */
    private final java.util.Set<UUID> vanishBeforeDelete = new java.util.HashSet<>();

    private final java.util.Set<UUID> vanishBeforeAdvance = new java.util.HashSet<>();

    private final java.util.Set<UUID> vanishBeforeReplace = new java.util.HashSet<>();

    private final InMemoryScimUserRepository users;

    private List<ScimGroupMembership> staleDormantMemberCandidates;

    private final List<UUID> lockedReads = new java.util.ArrayList<>();

    public InMemoryScimGroupRepository(InMemoryScimUserRepository users) {
        this.users = users;
    }

    /**
     * Makes the dormant-membership candidate query answer with these memberships, whatever the
     * stored state says — the window between the candidate read and the locked read, for the
     * reason {@link InMemoryScimUserRepository#answerDormancyCandidatesWith} gives.
     */
    public void answerDormantMemberCandidatesWith(List<ScimGroupMembership> memberships) {
        staleDormantMemberCandidates = List.copyOf(memberships);
    }

    @Override
    public ScimGroup create(ScimGroup group) {
        return insert(group, null);
    }

    @Override
    public ScimGroup createReserved(ScimGroup group, ReservedResourceName reservedName) {
        if (findByReservedName(reservedName).isPresent()) {
            throw new DuplicateDisplayNameException(
                    new IllegalStateException("that reservation is already held"));
        }
        return insert(group, reservedName);
    }

    @Override
    public Optional<ScimGroup> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }

    /**
     * No lock to take in memory; a single-threaded test has no second writer to exclude. The id
     * is recorded, so a test can assert the caller took the serialization point it promises.
     */
    @Override
    public Optional<ScimGroup> findByIdForUpdate(UUID id) {
        lockedReads.add(id);
        return findById(id);
    }

    /** Every id read through {@link #findByIdForUpdate}, in call order. */
    public List<UUID> lockedReads() {
        return List.copyOf(lockedReads);
    }

    @Override
    public Optional<ScimGroup> findByReservedName(ReservedResourceName reservedName) {
        return stored.values().stream()
                .filter(group -> reservedName == group.reservedName())
                .findFirst();
    }

    @Override
    public Optional<ScimGroup> replace(ScimGroup group) {
        if (vanishBeforeReplace.remove(group.id())) {
            stored.remove(group.id());
        }
        ScimGroup current = stored.get(group.id());
        if (current == null) {
            return Optional.empty();
        }
        requireDistinctDisplayName(group);
        requireLiveMembers(group);

        List<ScimGroupMember> members = withResolvedLabels(group.members());

        // Mirrors the adapter: a replacement that changed NOTHING advances no version, not even
        // the Group's own, so an idempotent re-send does not invalidate a connector's cached copy.
        // Kept in step deliberately — this fake and the adapter already disagreed once about
        // version bumps on create, and every unit test passed while the adapter was wrong. A fake
        // that is more eager than the adapter hides the adapter just as surely as one that is
        // lazier.
        boolean renamed = !current.displayName().equals(group.displayName());
        boolean membershipMoved = !userIdsOf(current.members()).equals(userIdsOf(members));
        if (!renamed && !membershipMoved) {
            return Optional.of(current);
        }

        ScimGroup written = new ScimGroup(
                current.id(),
                group.displayName(),
                members,
                // Carried across, not taken from the argument: a write cannot reserve or
                // un-reserve a Group.
                current.reservedName(),
                current.version() + 1,
                current.createdAt(),
                group.lastModifiedAt());
        stored.put(written.id(), written);
        advanceAffectedUsers(current, written, group.lastModifiedAt());
        return Optional.of(written);
    }

    @Override
    public Optional<ScimGroup> advanceVersion(UUID id, Instant now) {
        if (vanishBeforeAdvance.remove(id)) {
            stored.remove(id);
            return Optional.empty();
        }
        ScimGroup current = stored.get(id);
        if (current == null) {
            return Optional.empty();
        }
        ScimGroup written = new ScimGroup(current.id(), current.displayName(), current.members(),
                current.reservedName(), current.version() + 1, current.createdAt(), now);
        stored.put(id, written);
        return Optional.of(written);
    }

    private static java.util.Set<UUID> userIdsOf(List<ScimGroupMember> members) {
        return members.stream().map(ScimGroupMember::userId).collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public boolean deleteById(UUID id, Instant now) {
        if (vanishBeforeDelete.remove(id)) {
            stored.remove(id);
            return false;
        }
        ScimGroup removed = stored.remove(id);
        if (removed == null) {
            return false;
        }
        removed.members().forEach(member -> bumpUser(member.userId(), now));
        return true;
    }

    /**
     * Makes the next {@code deleteById} of this Group report that it removed nothing, while
     * {@code findById} still answers with the Group until then.
     *
     * <p>That is the one window the production {@code delete} cannot otherwise be made to walk:
     * it reads the Group, refuses a protected one, then deletes — and a concurrent transaction
     * may remove the row in between, which is why the delete REPORTS what the repository did
     * instead of assuming it succeeded. Against a real Postgres the window needs two
     * transactions to hit; here it is one call, and the behaviour reproduced is the adapter's
     * own: {@code DELETE} matched no row, so it returns false.
     */
    /**
     * Removes the Group as the next {@code advanceVersion} of it runs, which then reports absence —
     * the window in which a concurrent delete lands between an alias-only write and its bump.
     */
    public void vanishBeforeNextAdvance(UUID id) {
        vanishBeforeAdvance.add(id);
    }

    /**
     * Removes the Group as the next {@code replace} of it runs, which then reports absence — the
     * window in which a concurrent delete lands between a write's locked read and its replacement.
     */
    public void vanishBeforeNextReplace(UUID id) {
        vanishBeforeReplace.add(id);
    }

    public void vanishBeforeNextDelete(UUID id) {
        vanishBeforeDelete.add(id);
    }

    @Override
    public List<ScimGroup> findAllById(List<UUID> ids) {
        return ids.stream().map(stored::get).filter(java.util.Objects::nonNull).toList();
    }

    /** How many Groups are stored. */
    public long size() {
        return stored.size();
    }

    /** Every stored Group, in the order the persistent query's default order gives them. */
    @Override
    public List<ScimGroup> findAllOrderedByNormalizedDisplayName() {
        return stored.values().stream()
                .sorted(Comparator.comparing(group -> group.normalizedDisplayName().value()))
                .toList();
    }

    @Override
    public List<ScimGroupReference> findGroupsOfUser(UUID userId) {
        return stored.values().stream()
                .filter(group -> group.hasMember(userId))
                .sorted(Comparator.comparing(group -> group.normalizedDisplayName().value()))
                .map(group -> new ScimGroupReference(group.id(), group.displayName()))
                .toList();
    }

    @Override
    public boolean isMemberOfReservedGroup(UUID userId, ReservedResourceName reservedName) {
        return findByReservedName(reservedName)
                .map(group -> group.hasMember(userId))
                .orElse(false);
    }

    /**
     * Memberships of these Groups held by unreserved Users whose dormancy basis is before the
     * cutoff, by User then Group — or, once {@link #answerDormantMemberCandidatesWith} has been
     * called, exactly the memberships given.
     */
    @Override
    public List<ScimGroupMembership> findDormantMemberships(
            Collection<UUID> groupIds, Instant cutoff) {
        if (staleDormantMemberCandidates != null) {
            return staleDormantMemberCandidates;
        }
        return stored.values().stream()
                .filter(group -> groupIds.contains(group.id()))
                .flatMap(group -> group.members().stream()
                        .map(member -> new ScimGroupMembership(member.userId(), group.id())))
                .filter(membership -> users.findById(membership.userId())
                        .filter(user -> user.reservedName() == null)
                        // The candidate query's SQL predicate, basis < cutoff.
                        .filter(user -> user.dormancyBasis().isBefore(cutoff))
                        .isPresent())
                .sorted(Comparator.comparing(ScimGroupMembership::userId)
                        .thenComparing(ScimGroupMembership::groupId))
                .toList();
    }

    /** Drops one membership and advances the Group and the User, as the adapter does. */
    @Override
    public boolean removeMember(UUID groupId, UUID userId, Instant now) {
        ScimGroup current = stored.get(groupId);
        if (current == null || !current.hasMember(userId)) {
            return false;
        }
        stored.put(groupId, new ScimGroup(
                current.id(),
                current.displayName(),
                current.members().stream()
                        .filter(member -> !member.userId().equals(userId))
                        .toList(),
                current.reservedName(),
                current.version() + 1,
                current.createdAt(),
                now));
        bumpUser(userId, now);
        return true;
    }

    /** Puts a Group in the store directly, for a test arranging state rather than exercising a write. */
    public ScimGroup given(ScimGroup group) {
        stored.put(group.id(), group);
        return group;
    }

    private ScimGroup insert(ScimGroup group, ReservedResourceName reservedName) {
        requireDistinctDisplayName(group);
        requireLiveMembers(group);
        ScimGroup toStore = new ScimGroup(
                group.id(),
                group.displayName(),
                withResolvedLabels(group.members()),
                reservedName,
                group.version(),
                group.createdAt(),
                group.lastModifiedAt());
        stored.put(toStore.id(), toStore);
        toStore.members().forEach(member -> bumpUser(member.userId(), group.lastModifiedAt()));
        return toStore;
    }

    /** The unique constraint on the normalized display name, as the schema enforces it. */
    private void requireDistinctDisplayName(ScimGroup group) {
        boolean taken = stored.values().stream()
                .filter(other -> !other.id().equals(group.id()))
                .anyMatch(other -> other.normalizedDisplayName()
                        .equals(group.normalizedDisplayName()));
        if (taken) {
            throw new DuplicateDisplayNameException(
                    new IllegalStateException("that normalized displayName is already held"));
        }
    }

    /** The membership foreign key, as the schema enforces it: every member is a live User. */
    private void requireLiveMembers(ScimGroup group) {
        boolean unknown = group.members().stream()
                .anyMatch(member -> users.findById(member.userId()).isEmpty());
        if (unknown) {
            throw new UnknownGroupMemberException(
                    new IllegalStateException("a member does not name a live User"));
        }
    }

    /**
     * The membership with each member's label resolved from the referenced User, as the real
     * adapter's projection does — {@code display} is read-only, so a submitted one is replaced
     * rather than kept.
     */
    private List<ScimGroupMember> withResolvedLabels(List<ScimGroupMember> members) {
        return members.stream()
                .map(member -> new ScimGroupMember(member.userId(), label(member.userId())))
                .toList();
    }

    private String label(UUID userId) {
        return users.findById(userId)
                .map(user -> user.profile().displayName() != null
                        ? user.profile().displayName()
                        : user.profile().userName())
                .orElse(null);
    }

    /**
     * The version bumps the port promises: everyone whose rendered representation changed.
     *
     * <p>A rename is visible to every current member, so it advances the union; a membership
     * change is visible only to those added or removed, so it advances the symmetric difference.
     */
    private void advanceAffectedUsers(ScimGroup before, ScimGroup after, Instant now) {
        boolean renamed = !before.displayName().equals(after.displayName());
        before.members().forEach(member -> {
            if (renamed || !after.hasMember(member.userId())) {
                bumpUser(member.userId(), now);
            }
        });
        after.members().forEach(member -> {
            if (!before.hasMember(member.userId())) {
                bumpUser(member.userId(), now);
            }
        });
    }

    /**
     * Advances one User's version without touching anything else.
     *
     * <p>Done through the store's own map rather than through a User write, because this is not
     * a change to any of the User's own columns: the real adapter advances the resource row
     * directly, and routing it through a column write would be a different statement from the one
     * production makes.
     */
    private void bumpUser(UUID userId, Instant now) {
        users.findById(userId).ifPresent(user -> users.given(new ScimUser(
                user.id(),
                user.profile(),
                user.login(),
                user.reservedName(),
                user.version() + 1,
                user.createdAt(),
                now)));
    }
}
