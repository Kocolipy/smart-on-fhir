package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a set of direct Group memberships confers, as sign-in and the audit trail ask it: the union
 * of what the Groups carry, and the Superuser's expansion for the Admin group (ADR 0015).
 */
class GroupPermissionsTests {

    private static final UUID ADMINS = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID AUDITORS = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final UUID CARRIES_NOTHING =
            UUID.fromString("00000000-0000-4000-8000-0000000000a9");

    /** The Admin group carries nothing; Helpdesk and Auditors carry their Permissions. */
    private final GroupPermissions stored = storing(ADMINS, Map.of(
            HELPDESK, Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK),
            AUDITORS, Set.of(Permission.AUDIT_READ, Permission.USER_READ)));

    /** A source whose rows are {@code carried} and whose Admin group is {@code adminGroup}. */
    private static GroupPermissions storing(UUID adminGroup, Map<UUID, Set<Permission>> carried) {
        return new GroupPermissions() {
            @Override
            public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
                Map<UUID, Set<Permission>> answer = new LinkedHashMap<>();
                groupIds.stream().filter(carried::containsKey)
                        .forEach(group -> answer.put(group, carried.get(group)));
                return answer;
            }

            @Override
            public boolean confersSuperuser(Collection<UUID> groupIds) {
                return groupIds.contains(adminGroup);
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
        assertThat(stored.effectivePermissionsOf(List.of(CARRIES_NOTHING))).isEmpty();
    }

    @Test
    void membershipOfTheAdminGroupConfersTheSuperuser() {
        assertThat(stored.confersSuperuser(List.of(HELPDESK, ADMINS))).isTrue();
    }

    @Test
    void membershipOfOtherGroupsOnlyConfersNoSuperuser() {
        assertThat(stored.confersSuperuser(List.of(HELPDESK, CARRIES_NOTHING))).isFalse();
    }

    /**
     * The Superuser's session is expanded with every delegatable Permission in code, though the
     * Admin group carries none, so the effective Permissions recorded for it are expanded the same
     * way — beside whatever its other Groups carry.
     */
    @Test
    void theSuperusersEffectivePermissionsAreEveryDelegatablePermission() {
        assertThat(stored.effectivePermissionsOf(List.of(ADMINS)))
                .containsExactlyInAnyOrder(Permission.USER_READ, Permission.ACCOUNT_UNLOCK,
                        Permission.GROUP_READ, Permission.AUDIT_READ, Permission.CONNECTOR_READ,
                        Permission.OPS_READ);
    }

    @Test
    void aNonSuperusersEffectivePermissionsAreNotExpanded() {
        assertThat(stored.effectivePermissionsOf(List.of(AUDITORS)))
                .containsExactlyInAnyOrder(Permission.AUDIT_READ, Permission.USER_READ);
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
        GroupPermissions deleted = storing(ADMINS, Map.of(
                AUDITORS, Set.of(Permission.AUDIT_READ)));

        GroupPermissions remembered = deleted.remembering(HELPDESK, Set.of(Permission.OPS_READ));

        assertThat(remembered.permissionsOf(List.of(HELPDESK, AUDITORS)))
                .containsExactlyInAnyOrder(Permission.OPS_READ, Permission.AUDIT_READ);
        assertThat(remembered.permissionsOf(List.of(AUDITORS)))
                .containsExactly(Permission.AUDIT_READ);
        assertThat(remembered.confersSuperuser(List.of(ADMINS))).isTrue();
        assertThat(remembered.confersSuperuser(List.of(HELPDESK))).isFalse();
    }

    /** Remembering that a Group carried nothing leaves it carrying nothing. */
    @Test
    void rememberingNothingConfersNothing() {
        assertThat(stored.remembering(CARRIES_NOTHING, Set.of())
                        .carriedBy(List.of(CARRIES_NOTHING)))
                .isEmpty();
    }
}
