package com.example.backend.authorization.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a User's direct Group memberships confer: the one question sign-in, the Accounts list, the
 * Groups projection and the audit trail all ask of the Groups (ADR 0015).
 *
 * <p>A Group's Permissions are data, stored per Group. A Group that carries none confers nothing,
 * and the Admin group carries none: its direct members are the Superuser, expanded in code. Both
 * halves of a User's power — its Permissions and whether it is the Superuser — are answered
 * together, as one {@link ConferredPower}, so the Superuser rule is applied in one place
 * ({@link ConferredPower}) and no caller asks for either alone.
 *
 * <p>Membership is the directory's: a caller hands over the Groups each User is a direct member
 * of, as it read them or — for an audit's "before" — as they stood before a write.
 *
 * <p>Nothing here is the baseline: {@code patient:read} is held by every active User whatever its
 * Groups, so no membership confers it and no membership change moves it.
 */
public interface GroupPermissions {

    /**
     * The Permissions each of these Groups carries, before the Superuser's expansion. A Group that
     * carries none, or that does not exist, is absent from the answer.
     */
    Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds);

    /**
     * The Admin group: the Group carrying the {@code admin-group} reservation marker — never a
     * Group recognised by its name — or empty when no Group carries it.
     */
    Optional<UUID> adminGroup();

    /** The union of the Permissions these Groups carry, before the Superuser's expansion. */
    default Set<Permission> permissionsOf(Collection<UUID> groupIds) {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        carriedBy(groupIds).values().forEach(held::addAll);
        return Collections.unmodifiableSet(held);
    }

    /** What a User holding exactly these direct memberships holds beyond the baseline. */
    default ConferredPower powerOf(Collection<UUID> groupIds) {
        return answering(groupIds).apply(groupIds);
    }

    /**
     * What each of several Users — or several moments of one — holds beyond the baseline, each
     * from its own direct memberships, in one read of the Groups they name: their Permissions
     * once, and the Admin group once, however many are asked about. A key whose memberships are
     * empty holds {@link ConferredPower#NONE}, and when every one is empty nothing is read at all.
     */
    default <K> Map<K, ConferredPower> powersOf(
            Map<K, ? extends Collection<UUID>> membershipsByKey) {
        Set<UUID> named = new LinkedHashSet<>();
        membershipsByKey.values().forEach(named::addAll);
        Function<Collection<UUID>, ConferredPower> answer = answering(named);
        Map<K, ConferredPower> answers = new LinkedHashMap<>();
        membershipsByKey.forEach((key, memberships) -> answers.put(key, answer.apply(memberships)));
        return answers;
    }

    /**
     * The one read behind every answer: what the {@code named} Groups carry and which Group is the
     * Admin group, each read once, and the conferred power of any memberships among them derived
     * from that. Naming no Group reads nothing, since no membership among none confers anything.
     */
    private Function<Collection<UUID>, ConferredPower> answering(Collection<UUID> named) {
        if (named.isEmpty()) {
            return memberships -> ConferredPower.NONE;
        }
        Map<UUID, Set<Permission>> carried = carriedBy(named);
        Optional<UUID> adminGroup = adminGroup();
        return memberships -> ConferredPower.of(memberships, carried, adminGroup);
    }

    /**
     * Whether this Group's membership is a change of power: it is the Admin group, or it carries a
     * Permission. A Group that confers nothing moves nobody's Permissions by its membership.
     */
    default boolean confersPermissions(UUID groupId) {
        return !powerOf(List.of(groupId)).permissions().isEmpty();
    }

    /**
     * These answers, except that {@code groupId} still carries what it carried a moment ago: for a
     * write that has just removed the Group itself — a deletion, whose cascade takes the Group's
     * Permissions with it — and must still say what its former members held before it.
     */
    default GroupPermissions remembering(UUID groupId, Set<Permission> carried) {
        GroupPermissions current = this;
        return new GroupPermissions() {
            @Override
            public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
                Map<UUID, Set<Permission>> answer = new HashMap<>(current.carriedBy(groupIds));
                if (groupIds.contains(groupId) && !carried.isEmpty()) {
                    answer.put(groupId, carried);
                }
                return answer;
            }

            @Override
            public Optional<UUID> adminGroup() {
                return current.adminGroup();
            }
        };
    }
}
