package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a set of direct Group memberships confers, as sign-in, the Accounts list and the audit trail
 * ask it: the union of what the Groups carry, and the Superuser — with its expansion — for the Admin
 * group (ADR 0015), answered together.
 */
class GroupPermissionsTests {

    private static final UUID ADMINS = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID AUDITORS = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final UUID CARRIES_NOTHING =
            UUID.fromString("00000000-0000-4000-8000-0000000000a9");

    private static final UUID ADA = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    /** The Admin group carries nothing; Helpdesk and Auditors carry their Permissions. */
    private final GroupPermissions stored = storing(Optional.of(ADMINS), Map.of(
            HELPDESK, Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK),
            AUDITORS, Set.of(Permission.AUDIT_READ, Permission.USER_READ)));

    /** A source whose rows are {@code carried} and whose Admin group is {@code adminGroup}. */
    private static GroupPermissions storing(
            Optional<UUID> adminGroup, Map<UUID, Set<Permission>> carried) {
        return new GroupPermissions() {
            @Override
            public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
                Map<UUID, Set<Permission>> answer = new LinkedHashMap<>();
                groupIds.stream().filter(carried::containsKey)
                        .forEach(group -> answer.put(group, carried.get(group)));
                return answer;
            }

            @Override
            public Optional<UUID> adminGroup() {
                return adminGroup;
            }
        };
    }

    /** A User in several Groups holds the union of what they carry, each Permission once. */
    @Test
    void theGroupsPermissionsAreTheUnionOfWhatEachCarries() {
        assertThat(stored.permissionsOf(List.of(HELPDESK, AUDITORS)))
                .containsExactlyInAnyOrder(
                        Permission.USER_READ, Permission.ACCOUNT_UNLOCK, Permission.AUDIT_READ);
    }

    /** A Group with no Permissions confers nothing. */
    @Test
    void aGroupCarryingNothingConfersNothing() {
        assertThat(stored.powerOf(List.of(CARRIES_NOTHING)).permissions()).isEmpty();
    }

    /** A User in no Group holds nothing beyond the baseline, and is not the Superuser. */
    @Test
    void noMembershipConfersNothing() {
        assertThat(stored.powerOf(List.of())).isEqualTo(ConferredPower.NONE);
    }

    @Test
    void membershipOfTheAdminGroupConfersTheSuperuser() {
        assertThat(stored.powerOf(List.of(HELPDESK, ADMINS)).superuser()).isTrue();
    }

    @Test
    void membershipOfOtherGroupsOnlyConfersNoSuperuser() {
        assertThat(stored.powerOf(List.of(HELPDESK, CARRIES_NOTHING)).superuser()).isFalse();
    }

    /** Where no Group carries the marker, nobody is the Superuser, whatever their Groups. */
    @Test
    void nobodyIsTheSuperuserWhereNoGroupIsTheAdminGroup() {
        GroupPermissions noAdminGroup = storing(Optional.empty(), Map.of());

        assertThat(noAdminGroup.powerOf(List.of(ADMINS, HELPDESK))).isEqualTo(ConferredPower.NONE);
    }

    /**
     * The Superuser's session is expanded with every delegatable Permission in code, though the
     * Admin group carries none, so the effective Permissions recorded for it are expanded the same
     * way — beside whatever its other Groups carry.
     */
    @Test
    void theSuperusersEffectivePermissionsAreEveryDelegatablePermission() {
        assertThat(stored.powerOf(List.of(ADMINS)).permissions())
                .containsExactlyInAnyOrder(Permission.USER_READ, Permission.ACCOUNT_UNLOCK,
                        Permission.GROUP_READ, Permission.AUDIT_READ, Permission.CONNECTOR_READ,
                        Permission.OPS_READ);
    }

    @Test
    void aNonSuperusersEffectivePermissionsAreNotExpanded() {
        assertThat(stored.powerOf(List.of(AUDITORS)).permissions())
                .containsExactlyInAnyOrder(Permission.AUDIT_READ, Permission.USER_READ);
    }

    /**
     * Several Users' conferred power, each from its own memberships, in one answer: each holds what its
     * own Groups confer and nothing another User's Groups do.
     */
    @Test
    void eachUserIsAnsweredFromItsOwnMemberships() {
        assertThat(stored.powersOf(Map.of(
                        ADA, List.of(HELPDESK),
                        BOB, List.of(AUDITORS, ADMINS))))
                .isEqualTo(Map.of(
                        ADA, new ConferredPower(
                                Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK), false),
                        BOB, new ConferredPower(Permission.allDelegatable(), true)));
    }

    /**
     * Users in no Group are answered without reading the Groups at all: a sign-in or an Accounts
     * row with no memberships costs no statement.
     */
    @Test
    void usersInNoGroupAreAnsweredWithoutReadingTheGroups() {
        GroupPermissions unreadable = new GroupPermissions() {
            @Override
            public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
                throw new AssertionError("read what the Groups carry");
            }

            @Override
            public Optional<UUID> adminGroup() {
                throw new AssertionError("read the Admin group");
            }
        };

        assertThat(unreadable.powersOf(Map.of(ADA, List.of(), BOB, List.of())))
                .isEqualTo(Map.of(ADA, ConferredPower.NONE, BOB, ConferredPower.NONE));
    }

    /** A User asked about with no Groups is answered, holding nothing. */
    @Test
    void aUserInNoGroupIsAnsweredWithNothing() {
        assertThat(stored.powersOf(Map.of(ADA, List.of(), BOB, List.of(HELPDESK))))
                .containsEntry(ADA, ConferredPower.NONE);
    }

    /**
     * Whether a Group's membership is a change of power at all: the Admin group, and a Group
     * carrying anything, confer something; a Group carrying nothing does not.
     */
    @Test
    void aGroupConfersPermissionsWhenItsMembershipGrantsAnything() {
        assertThat(List.of(ADMINS, HELPDESK, CARRIES_NOTHING))
                .map(stored::confersPermissions)
                .containsExactly(true, true, false);
    }

    /**
     * A deleted Group's rows are gone, yet its former members' "before" must still be what it
     * carried: remembered, that Group answers as it did, and every other Group as it does now.
     */
    @Test
    void aRememberedGroupStillCarriesWhatItDid() {
        GroupPermissions deleted = storing(Optional.of(ADMINS), Map.of(
                AUDITORS, Set.of(Permission.AUDIT_READ)));

        GroupPermissions remembered = deleted.remembering(HELPDESK, Set.of(Permission.OPS_READ));

        assertThat(remembered.permissionsOf(List.of(HELPDESK, AUDITORS)))
                .containsExactlyInAnyOrder(Permission.OPS_READ, Permission.AUDIT_READ);
        assertThat(remembered.permissionsOf(List.of(AUDITORS)))
                .containsExactly(Permission.AUDIT_READ);
        assertThat(remembered.powerOf(List.of(ADMINS)).superuser()).isTrue();
        assertThat(remembered.powerOf(List.of(HELPDESK)).superuser()).isFalse();
    }

    /** The remembered Group is answered only when asked about, as every other Group is. */
    @Test
    void aRememberedGroupIsAnsweredOnlyWhenAskedAbout() {
        GroupPermissions remembered =
                stored.remembering(CARRIES_NOTHING, Set.of(Permission.OPS_READ));

        assertThat(remembered.carriedBy(List.of(AUDITORS)))
                .isEqualTo(Map.of(AUDITORS, Set.of(Permission.AUDIT_READ, Permission.USER_READ)));
    }

    /** Remembering that a Group carried nothing leaves it carrying nothing. */
    @Test
    void rememberingNothingConfersNothing() {
        assertThat(stored.remembering(CARRIES_NOTHING, Set.of())
                        .carriedBy(List.of(CARRIES_NOTHING)))
                .isEmpty();
    }
}
