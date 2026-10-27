package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One page of a Group's members: what it holds, read back component by component. */
class GroupMemberPageTests {

    private static final GroupMemberPage.Member ADA = new GroupMemberPage.Member(
            UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "ada", "Ada", true);

    @Test
    void aPageHoldsItsTotalItsStartAndItsMembers() {
        GroupMemberPage page = new GroupMemberPage(41, 21, List.of(ADA));

        assertThat(List.of(page.totalResults(), page.startIndex(), page.members()))
                .containsExactly(41L, 21, List.of(ADA));
    }

    /** The members are copied: a caller's list changing afterwards changes nothing. */
    @Test
    void aPageKeepsTheMembersItWasGiven() {
        List<GroupMemberPage.Member> given = new ArrayList<>(List.of(ADA));
        GroupMemberPage page = new GroupMemberPage(1, 1, given);

        given.clear();

        assertThat(page.members()).containsExactly(ADA);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> page.members().add(ADA));
    }

    /**
     * A member row is the User's identity as the Users projection shows it — its stable id,
     * {@code userName}, display name and {@code active} — read from the User whatever its login
     * state holds.
     */
    @Test
    void aMemberIsTheUsersIdentityAndStanding() {
        UUID id = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
        ScimUser user = ScimUser.created(id,
                new ScimUserProfile("grace", null, "Grace Hopper", null, null, null, false, List.of()),
                "{bcrypt}not-a-real-hash", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(GroupMemberPage.Member.of(user))
                .isEqualTo(new GroupMemberPage.Member(id, "grace", "Grace Hopper", false));
    }
}
