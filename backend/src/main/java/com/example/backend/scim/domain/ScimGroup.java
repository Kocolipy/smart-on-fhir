package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A SCIM Group as the directory holds it: a display name, a set of direct User members,
 * and the version every resource carries.
 *
 * <p>A Group is how this directory expresses authority. The Admin group's direct members are
 * the Superuser (ADR 0015), which is why a Group may be reserved: {@link ReservedResourceName#ADMIN_GROUP} marks the one Group that
 * cannot be renamed or deleted, and whose recovery member cannot be removed.
 *
 * <p><strong>Direct User members only.</strong> {@code members} is a list of
 * {@link ScimGroupMember}, each naming a User, and there is no representation for a Group
 * as a member — so nested and transitive membership are not unsupported by a check that
 * could be forgotten, they are unsupported because there is nothing to write them into.
 * The schema says the same thing a second way: {@code scim_group_members.user_id} has a
 * foreign key to the User table, not to the shared resource table.
 *
 * <p>Membership is de-duplicated in the canonical constructor, keeping the order the
 * connector sent. SCIM does not require {@code members} to be ordered, but a listing that
 * reshuffles between reads makes a diff-based client rewrite the resource forever, and a
 * duplicate would violate the stored pair's primary key rather than being silently
 * collapsed by the database.
 *
 * @param id             stable, non-reassignable, unique across Users and Groups
 * @param displayName    the Group's label, as submitted; what is rendered back
 * @param members        direct User members, de-duplicated, never null
 * @param reservedName   the reservation protecting this Group, or {@code null}
 * @param version        monotonic representation version, from 1
 * @param createdAt      when the resource was created, UTC
 * @param lastModifiedAt when its representation last changed, UTC
 */
public record ScimGroup(
        UUID id,
        String displayName,
        List<ScimGroupMember> members,
        ReservedResourceName reservedName,
        long version,
        Instant createdAt,
        Instant lastModifiedAt) {

    public ScimGroup {
        if (id == null) {
            throw new IllegalArgumentException("a SCIM Group has a stable id");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("a SCIM Group has a displayName");
        }
        if (version < ScimUser.INITIAL_VERSION) {
            throw new IllegalArgumentException("a live SCIM Group's version starts at 1");
        }
        members = deduplicated(members);
    }

    /**
     * A newly created Group: version 1, unreserved, and one timestamp used for both
     * {@code created} and {@code lastModified} because nothing has changed since.
     *
     * <p>Unreserved unconditionally, and there is deliberately no factory that produces a
     * reserved Group: the marker is written by seeding's own statement, so no provisioning
     * path can create a Group that protects itself from being changed — or, worse, one
     * that claims the Admin group's authority.
     */
    public static ScimGroup created(
            UUID id, String displayName, List<ScimGroupMember> members, Instant now) {
        return new ScimGroup(
                id, displayName, members, null, ScimUser.INITIAL_VERSION, now, now);
    }

    /** The form {@code displayName} uniqueness is decided on. */
    public NormalizedDisplayName normalizedDisplayName() {
        return NormalizedDisplayName.of(displayName);
    }

    /**
     * Whether SCIM writes against this Group are refused — its rename, its deletion, and
     * the removal of its reserved member.
     */
    public boolean isProtectedFromWrites() {
        return reservedName != null;
    }

    /**
     * The Group as a write asks for it: the display name and the membership it should now
     * have, with {@code lastModified} moved to when the write happened.
     *
     * <p>One transition for both PUT and PATCH, because by the time either reaches the
     * domain it has decided the same two things: what the display name is now and who the
     * members are now.
     *
     * <p><strong>The version is deliberately carried unchanged.</strong> This value is the
     * state a write is asking for, not the state that was stored — and the version has to
     * be advanced by the database, because a membership change advances OTHER resources'
     * versions too and because an increment computed here could be lost by a concurrent
     * one. {@code ScimGroupRepository#replace} owns that and returns the stored result;
     * a caller that wants the new version reads it from there rather than from this.
     *
     * <p>The reservation is carried across rather than taken as an argument: a write cannot
     * reserve a Group and cannot un-reserve one.
     */
    public ScimGroup replacedWith(
            String newDisplayName, List<ScimGroupMember> newMembers, Instant now) {
        return new ScimGroup(
                id, newDisplayName, newMembers, reservedName, version, createdAt, now);
    }

    /** Whether this User is a direct member. */
    public boolean hasMember(UUID userId) {
        return members.stream().anyMatch(member -> member.userId().equals(userId));
    }

    /**
     * The submitted membership with repeats removed and order preserved.
     *
     * <p>Keyed on the referenced id alone, so two entries naming the same User collapse to
     * one even when they carry different {@code display} labels — which a connector may
     * send, and which is read-only anyway. The FIRST occurrence wins, so the order is the
     * order of first mention.
     */
    private static List<ScimGroupMember> deduplicated(List<ScimGroupMember> submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return List.of();
        }
        Map<UUID, ScimGroupMember> byUserId = new LinkedHashMap<>();
        for (ScimGroupMember member : submitted) {
            byUserId.putIfAbsent(member.userId(), member);
        }
        return List.copyOf(byUserId.values());
    }
}
