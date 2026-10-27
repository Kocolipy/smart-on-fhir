package com.example.backend.auth.application;

import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import java.util.List;
import java.util.UUID;

/**
 * One page of a Group's direct members, as the Groups projection reads them on expanding a Group's
 * row: who they are, in the Users projection's order (normalized {@code userName}), and how many
 * there are in all.
 *
 * <p>Member identities are Users-projection data, so the read requires {@code user:read} beside
 * {@code group:read}, which alone discloses only the count (ADR 0015). It is also the shape the
 * endpoint puts on the wire, serialised as-is as {@link IdentitySummary} is: the openapi
 * {@code GroupMemberPage} schema is this record's documented counterpart.
 *
 * @param totalResults how many direct members the Group has, whatever the page
 * @param startIndex   the one-based position of the page's first member
 * @param members      the page's members, in order; empty past the last one, and for a Group with
 *                     none
 */
public record GroupMemberPage(long totalResults, int startIndex, List<Member> members) {

    public GroupMemberPage {
        members = List.copyOf(members);
    }

    /**
     * A direct member as the Users projection identifies it — and nothing more. There is no
     * component a password hash, or any of the User's login state, could be written into: a
     * member row cannot carry a credential, by construction rather than by review.
     *
     * @param id          the User's stable id
     * @param userName    its login and display attribute, directory-owned
     * @param displayName the directory's display name, or {@code null} when it sets none
     * @param active      the directory's {@code active} attribute
     */
    public record Member(UUID id, String userName, String displayName, boolean active) {

        /** The member row for a User: its id and its profile's identity, nothing of its login. */
        public static Member of(ScimUser user) {
            ScimUserProfile profile = user.profile();
            return new Member(user.id(), profile.userName(), profile.displayName(), profile.active());
        }
    }
}
