package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a set of direct Group memberships confers, as the audit trail records it before and after a
 * membership change: the Permissions, and whether the Superuser is among them (ADR 0015).
 */
class GroupPermissionsTests {

    private static final UUID ADMINS = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID EMPTY_ROLE = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final UUID UNMAPPED = UUID.fromString("00000000-0000-4000-8000-0000000000a9");

    private final GroupPermissions mapping = RoleMapping.of(
            List.of(new RoleDefinition("Superuser", Arrays.stream(Permission.values())
                            .filter(permission -> !permission.tokenOnly())
                            .map(Permission::value).toList()),
                    new RoleDefinition("Helpdesk", List.of("user:read", "account:unlock")),
                    new RoleDefinition("Nothing", List.of())),
            List.of(new GroupAssignment(ADMINS, "Superuser", true),
                    new GroupAssignment(HELPDESK, "Helpdesk", false),
                    new GroupAssignment(EMPTY_ROLE, "Nothing", false)));

    /** A stand-in whose Groups confer {@code conferred}, and the Superuser when {@code superuser}. */
    private static GroupPermissions conferring(Set<Permission> conferred, boolean superuser) {
        return new GroupPermissions() {
            @Override
            public Set<Permission> permissionsOf(Collection<UUID> groupIds) {
                return conferred;
            }

            @Override
            public boolean confersSuperuser(Collection<UUID> groupIds) {
                return superuser;
            }
        };
    }

    @Test
    void membershipOfTheAdminGroupConfersTheSuperuser() {
        assertThat(mapping.confersSuperuser(List.of(HELPDESK, ADMINS))).isTrue();
    }

    @Test
    void membershipOfOtherGroupsOnlyConfersNoSuperuser() {
        assertThat(mapping.confersSuperuser(List.of(HELPDESK, UNMAPPED))).isFalse();
    }

    @Test
    void aGroupsEffectivePermissionsAreWhatItsRoleHolds() {
        assertThat(mapping.effectivePermissionsOf(List.of(HELPDESK, UNMAPPED)))
                .containsExactlyInAnyOrder(Permission.USER_READ, Permission.ACCOUNT_UNLOCK);
    }

    /**
     * The Superuser's session is expanded with every delegatable Permission in code, whatever its
     * Groups carry, so the effective Permissions recorded for it are expanded the same way.
     */
    @Test
    void theSuperusersEffectivePermissionsIncludeEveryDelegatablePermission() {
        assertThat(conferring(Set.of(Permission.PATIENT_READ), true)
                        .effectivePermissionsOf(List.of(ADMINS)))
                .containsExactlyInAnyOrder(Permission.PATIENT_READ, Permission.USER_READ,
                        Permission.ACCOUNT_UNLOCK, Permission.GROUP_READ, Permission.AUDIT_READ,
                        Permission.CONNECTOR_READ, Permission.OPS_READ);
    }

    @Test
    void aNonSuperusersEffectivePermissionsAreNotExpanded() {
        assertThat(conferring(Set.of(Permission.AUDIT_READ), false)
                        .effectivePermissionsOf(List.of(HELPDESK)))
                .containsExactly(Permission.AUDIT_READ);
    }

    /**
     * Whether a Group's membership is a change of power at all: the Admin group, and a Group whose
     * Role holds anything, confer something; a Role holding nothing and an unmapped Group do not.
     */
    @Test
    void aGroupConfersPermissionsWhenItsMembershipGrantsAnything() {
        assertThat(List.of(ADMINS, HELPDESK, EMPTY_ROLE, UNMAPPED))
                .map(mapping::confersPermissions)
                .containsExactly(true, true, false, false);
    }

    /** The Admin group confers the Superuser even where its own Permissions would be none. */
    @Test
    void theAdminGroupConfersPermissionsWhateverItCarries() {
        assertThat(conferring(Set.of(), true).confersPermissions(ADMINS)).isTrue();
    }
}
