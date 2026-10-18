package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Which resources' rendered documents a SCIM write changed, and so whose
 * <em>Resource version</em> — and ETag — it advances.
 *
 * <p>One rule, decided here and nowhere else: <em>every resource whose representation a write
 * changed advances its version exactly once, and no other resource advances.</em> A store
 * describes the write — the membership before and after, the old and new name, what was deleted
 * — through one of the factories below, and applies the answer, {@link #advanced()} at
 * {@link #at()}, without adding to it or leaving anything out. No store names the ids itself,
 * because a call site that had to work out who moved is a call site that eventually forgets one:
 * Group create with members once advanced none of them, and a miss is silent — a connector keeps
 * a stale ETag rather than receiving an error. That includes the in-memory stores the unit tests
 * run against: a second copy of the rule there would let the tests pass against the old rule
 * after this one changed.
 *
 * <p>Where a Group's change shows up in a User's document, and the reverse:
 *
 * <ul>
 *   <li>A User's rendered {@code groups} lists every Group it belongs to, with the Group's
 *       {@code displayName} as the label. So a membership that starts or stops changes that
 *       User, and a Group rename changes every User still in it.
 *   <li>A Group's rendered {@code members} lists its Users, each with its label — its
 *       {@code displayName}, or its {@code userName} when it has none. So a User deleted out of
 *       a Group changes the Group, and a User write that changes the label changes every Group
 *       the User is in.
 * </ul>
 *
 * <p>Pure: it decides from the sets it is given and touches nothing. Carrying the decision out is
 * the persistence adapter's ({@code scim.infrastructure.persistence.ResourceVersionStatements}),
 * which issues the statements in chunks small enough for SQL Server's parameter limit. Locking is
 * not this module's concern either, but the decision relies on the lock module
 * ({@link ScimWriteLocks}): every resource it names is one the write already holds, locked in the
 * canonical order before the write began, so the bulk update takes no new lock and the order it
 * updates rows in cannot matter. The lock module locks a superset of what is decided here — a
 * User's Groups whether or not its label moved, a Group's members before and after whether or not
 * each one's rendering moved — so this rule can change without the locks having to follow.
 */
public final class ResourceVersionAdvance {

    private final Set<UUID> advanced;
    private final Instant at;

    private ResourceVersionAdvance(Collection<UUID> advanced, Instant at) {
        this.advanced = Set.copyOf(advanced);
        this.at = Objects.requireNonNull(at, "at");
    }

    /**
     * A write to the User's own attributes that cannot move its label — a completed password
     * change, the calling connector's {@code externalId} alias written alone. Only that User's
     * document changed: its Groups render their members by id and label, and neither moved.
     */
    public static ResourceVersionAdvance userWritten(UUID userId, Instant now) {
        return new ResourceVersionAdvance(List.of(userId), now);
    }

    /**
     * A User's stored attributes replaced — the shape every User PUT and PATCH arrives in.
     *
     * <ul>
     *   <li>The label each of its Groups renders it with ({@code members.display}) is unchanged:
     *       only the User's document changed.
     *   <li>The label changed: the User, and every Group it is a member of, because each one's
     *       {@code members} now shows it differently. Compared exactly, so a change of case alone
     *       is a change.
     * </ul>
     *
     * @param previousLabel the label as the Groups rendered it before the write
     * @param label         the label they render after it
     * @param groupsOfUser  the Groups the User is a direct member of
     */
    public static ResourceVersionAdvance userReplaced(
            UUID userId,
            String previousLabel,
            String label,
            Collection<UUID> groupsOfUser,
            Instant now) {
        if (previousLabel.equals(label)) {
            return userWritten(userId, now);
        }
        Set<UUID> changed = new LinkedHashSet<>();
        changed.add(userId);
        changed.addAll(groupsOfUser);
        return new ResourceVersionAdvance(changed, now);
    }

    /**
     * A User deleted. Every Group it belonged to lost a member; the User itself has no version
     * left to advance.
     */
    public static ResourceVersionAdvance userDeleted(Collection<UUID> groupsOfUser, Instant now) {
        return new ResourceVersionAdvance(groupsOfUser, now);
    }

    /**
     * A Group created with these members. Each member's {@code groups} gained the new Group. The
     * Group's own version is not advanced: it starts at its initial value by definition.
     */
    public static ResourceVersionAdvance groupCreated(
            Collection<UUID> members, Instant lastModifiedAt) {
        return new ResourceVersionAdvance(members, lastModifiedAt);
    }

    /**
     * A Group's name and membership replaced — the shape every Group PUT and PATCH arrives in.
     *
     * <ul>
     *   <li>Neither changed: nothing advances, not even the Group. A connector converging on a
     *       desired state re-sends identical state constantly, and moving the version on each
     *       re-send would invalidate every cached copy for a write that changed nothing.
     *   <li>Only the membership changed: the Group, and the Users whose membership started or
     *       stopped — the symmetric difference. A member removed and re-added in the same write is
     *       in both sets and does not advance.
     *   <li>The name changed: the Group, and every member before or after, because the label in
     *       each one's {@code groups} changed, gained or vanished.
     * </ul>
     */
    public static ResourceVersionAdvance groupReplaced(
            UUID groupId,
            String previousDisplayName,
            String displayName,
            Collection<UUID> previousMembers,
            Collection<UUID> members,
            Instant lastModifiedAt) {
        boolean renamed = !previousDisplayName.equals(displayName);
        Set<UUID> movedMembers = renamed
                ? union(previousMembers, members)
                : symmetricDifference(previousMembers, members);
        if (!renamed && movedMembers.isEmpty()) {
            return new ResourceVersionAdvance(List.of(), lastModifiedAt);
        }
        Set<UUID> changed = new LinkedHashSet<>();
        changed.add(groupId);
        changed.addAll(movedMembers);
        return new ResourceVersionAdvance(changed, lastModifiedAt);
    }

    /**
     * A change to the Group's representation that no Group column records — an {@code externalId}
     * alias written alone. Only the Group's document changed.
     */
    public static ResourceVersionAdvance groupTouched(UUID groupId, Instant now) {
        return new ResourceVersionAdvance(List.of(groupId), now);
    }

    /**
     * A Group deleted. Every former member's {@code groups} lost it; the Group itself has no
     * version left to advance.
     */
    public static ResourceVersionAdvance groupDeleted(Collection<UUID> formerMembers, Instant now) {
        return new ResourceVersionAdvance(formerMembers, now);
    }

    /** One membership removed: the Group lost a member and the User lost a Group. */
    public static ResourceVersionAdvance memberRemoved(UUID groupId, UUID userId, Instant now) {
        return new ResourceVersionAdvance(List.of(groupId, userId), now);
    }

    /** The resources whose version this write advances, each once. */
    public Set<UUID> advanced() {
        return advanced;
    }

    /** The {@code lastModified} each advanced resource records. */
    public Instant at() {
        return at;
    }

    private static Set<UUID> symmetricDifference(
            Collection<UUID> previous, Collection<UUID> current) {
        Set<UUID> difference = union(previous, current);
        Set<UUID> unchanged = new LinkedHashSet<>(previous);
        unchanged.retainAll(current);
        difference.removeAll(unchanged);
        return difference;
    }

    private static Set<UUID> union(Collection<UUID> previous, Collection<UUID> current) {
        Set<UUID> all = new LinkedHashSet<>(previous);
        all.addAll(current);
        return all;
    }
}
