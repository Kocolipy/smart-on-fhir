package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The role mapping's rules: the closed Permission set, validation, the union, and the hash.
 * {@code RoleMappingStartupTests} drives the same validation through a context refresh.
 */
class RoleMappingTests {

    private static final List<String> EVERY =
            Arrays.stream(Permission.values()).map(Permission::value).toList();

    private static final UUID ADMINS = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID AUDITORS = UUID.fromString("00000000-0000-4000-8000-0000000000a3");

    private static final RoleDefinition SUPERUSER = new RoleDefinition("Superuser", EVERY);
    private static final RoleDefinition HELPDESK_ROLE =
            new RoleDefinition("Helpdesk", List.of("user:read", "user:write"));
    private static final RoleDefinition AUDITOR_ROLE =
            new RoleDefinition("Auditor", List.of("audit:read", "user:read"));

    private final RoleMapping mapping = RoleMapping.of(
            List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE),
            List.of(
                    new GroupAssignment(ADMINS, "Superuser", true),
                    new GroupAssignment(HELPDESK, "Helpdesk", false),
                    new GroupAssignment(AUDITORS, "Auditor", false)));

    // The vocabulary

    /** The issue's list, exactly: a Permission is added or removed only by changing this test. */
    @Test
    void thePermissionSetIsClosedAndSpelledAsSpecified() {
        assertThat(Arrays.stream(Permission.values()).map(Permission::value)).containsExactly(
                "user:read", "user:write", "group:read", "group:write", "audit:read",
                "connector:read", "connector:write", "connector:token", "ops:read",
                "patient:read");
    }

    @Test
    void aPermissionIsLookedUpByItsExactSpelling() {
        assertThat(Permission.fromValue("connector:token")).contains(Permission.CONNECTOR_TOKEN);
        assertThat(Permission.fromValue("CONNECTOR:TOKEN")).isEmpty();
        assertThat(Permission.fromValue("CONNECTOR_TOKEN")).isEmpty();
        assertThat(Permission.fromValue(null)).isEmpty();
    }

    @Test
    void permissionsSortByTheirSpelling() {
        assertThat(Arrays.stream(Permission.values()).sorted(Permission.BY_VALUE)
                        .map(Permission::value))
                .isSorted()
                .first().isEqualTo("audit:read");
    }

    // Resolution

    @Test
    void severalMappedGroupsConferTheUnionOfTheirRoles() {
        assertThat(mapping.permissionsOf(List.of(HELPDESK, AUDITORS))).containsExactlyInAnyOrder(
                Permission.USER_READ, Permission.USER_WRITE, Permission.AUDIT_READ);
    }

    @Test
    void oneMappedGroupConfersExactlyItsRole() {
        assertThat(mapping.permissionsOf(List.of(AUDITORS)))
                .containsExactlyInAnyOrder(Permission.AUDIT_READ, Permission.USER_READ);
    }

    @Test
    void noMappedGroupConfersNothing() {
        assertThat(mapping.permissionsOf(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(mapping.permissionsOf(List.of())).isEmpty();
    }

    @Test
    void theSuperuserGroupConfersEveryPermission() {
        assertThat(mapping.permissionsOf(List.of(ADMINS))).containsExactlyInAnyOrder(
                Permission.values());
        assertThat(mapping.superuserGroupId()).isEqualTo(ADMINS);
    }

    @Test
    void reportsEveryMappedGroupInConfigurationOrder() {
        assertThat(mapping.mappedGroupIds()).containsExactly(ADMINS, HELPDESK, AUDITORS);
    }

    /** What the roles endpoint reads: the Role each mapped Group confers, and none for others. */
    @Test
    void eachMappedGroupConfersItsRoleAndAnUnmappedGroupNone() {
        assertThat(mapping.roleOf(HELPDESK)).hasValueSatisfying(role -> {
            assertThat(role.name()).isEqualTo("Helpdesk");
            assertThat(role.permissions())
                    .containsExactly(Permission.USER_READ, Permission.USER_WRITE);
        });
        assertThat(mapping.roleOf(ADMINS)).map(Role::name).contains("Superuser");
        assertThat(mapping.roleOf(UUID.randomUUID())).isEmpty();
    }

    /** Every defined Role in configuration order — a Role no Group confers included. */
    @Test
    void reportsEveryRoleAndEveryAssignmentInConfigurationOrder() {
        RoleMapping withUnassigned = RoleMapping.of(
                List.of(HELPDESK_ROLE, SUPERUSER, new RoleDefinition("Unassigned", List.of())),
                List.of(new GroupAssignment(HELPDESK, "Helpdesk", false),
                        new GroupAssignment(ADMINS, "Superuser", true)));

        assertThat(withUnassigned.roles()).extracting(Role::name)
                .containsExactly("Helpdesk", "Superuser", "Unassigned");
        assertThat(withUnassigned.assignments().keySet()).containsExactly(HELPDESK, ADMINS);
        assertThat(withUnassigned.assignments().get(ADMINS).name()).isEqualTo("Superuser");
        assertThatThrownBy(() -> withUnassigned.assignments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> withUnassigned.roles().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRoleMayHoldNoPermission() {
        RoleMapping withEmpty = RoleMapping.of(
                List.of(SUPERUSER, new RoleDefinition("Nobody", null)),
                List.of(new GroupAssignment(ADMINS, "Superuser", true),
                        new GroupAssignment(HELPDESK, "Nobody", false)));

        assertThat(withEmpty.permissionsOf(List.of(HELPDESK))).isEmpty();
    }

    @Test
    void theResolvedSetCannotBeChangedByItsReader() {
        Set<Permission> held = mapping.permissionsOf(List.of(AUDITORS));

        assertThatThrownBy(() -> held.add(Permission.OPS_READ))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(mapping.permissionsOf(List.of(AUDITORS))).doesNotContain(Permission.OPS_READ);
    }

    // The hash

    /**
     * The exact canonical rendering the hash digests, spelled out here: Roles sorted by name with
     * their Permissions sorted by name, then entries sorted by Group id with the Role and the
     * Superuser marker. Pinned because the hash must be the same in every process — a session
     * minted before a restart is checked against the hash the restarted application computes — and
     * a rendering that leaned on any iteration order would only look stable inside one JVM.
     */
    @Test
    void theHashIsSha256OfTheCanonicalRendering() throws Exception {
        String canonical = "role\tAuditor\taudit:read,user:read\n"
                + "role\tHelpdesk\tuser:read,user:write\n"
                + "role\tSuperuser\t" + String.join(",", EVERY.stream().sorted().toList()) + "\n"
                + "group\t" + ADMINS + "\tSuperuser\tsuperuser\n"
                + "group\t" + HELPDESK + "\tHelpdesk\t-\n"
                + "group\t" + AUDITORS + "\tAuditor\t-\n";
        RoleMapping outOfOrder = RoleMapping.of(
                List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE),
                List.of(
                        new GroupAssignment(AUDITORS, "Auditor", false),
                        new GroupAssignment(HELPDESK, "Helpdesk", false),
                        new GroupAssignment(ADMINS, "Superuser", true)));

        assertThat(outOfOrder.hash()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /** A Role renamed — same Permissions, same Groups — is a different mapping. */
    @Test
    void renamingARoleChangesTheHash() {
        RoleMapping renamed = RoleMapping.of(
                List.of(SUPERUSER, HELPDESK_ROLE,
                        new RoleDefinition("Reviewer", List.of("audit:read", "user:read"))),
                List.of(new GroupAssignment(ADMINS, "Superuser", true),
                        new GroupAssignment(HELPDESK, "Helpdesk", false),
                        new GroupAssignment(AUDITORS, "Reviewer", false)));

        assertThat(renamed.hash()).isNotEqualTo(mapping.hash());
    }
    @Test
    void theHashIsHexSha256AndStableAcrossListOrder() {
        RoleMapping reordered = RoleMapping.of(
                List.of(AUDITOR_ROLE,
                        new RoleDefinition("Helpdesk", List.of("user:write", "user:read")),
                        new RoleDefinition("Superuser", EVERY.reversed())),
                List.of(
                        new GroupAssignment(AUDITORS, "Auditor", false),
                        new GroupAssignment(ADMINS, "Superuser", true),
                        new GroupAssignment(HELPDESK, "Helpdesk", false)));

        assertThat(mapping.hash()).matches("[0-9a-f]{64}").isEqualTo(reordered.hash());
    }

    @Test
    void anyChangeOfMeaningChangesTheHash() {
        String original = mapping.hash();

        assertThat(RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE,
                                new RoleDefinition("Auditor", List.of("audit:read"))),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Helpdesk", false),
                                new GroupAssignment(AUDITORS, "Auditor", false)))
                        .hash())
                .as("a Permission removed from a Role").isNotEqualTo(original);
        assertThat(RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Auditor", false),
                                new GroupAssignment(AUDITORS, "Helpdesk", false)))
                        .hash())
                .as("two Groups' Roles swapped").isNotEqualTo(original);
        assertThat(RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Helpdesk", false)))
                        .hash())
                .as("a mapping entry removed").isNotEqualTo(original);
        assertThat(RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE,
                                new RoleDefinition("Unused", List.of("ops:read"))),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Helpdesk", false),
                                new GroupAssignment(AUDITORS, "Auditor", false)))
                        .hash())
                .as("a Role defined").isNotEqualTo(original);
        assertThat(RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE,
                                new RoleDefinition("Superuser too", EVERY)),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Superuser too", false),
                                new GroupAssignment(AUDITORS, "Auditor", false)))
                        .hash())
                .isNotEqualTo(RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE,
                                new RoleDefinition("Superuser too", EVERY)),
                        List.of(new GroupAssignment(ADMINS, "Superuser", false),
                                new GroupAssignment(HELPDESK, "Superuser too", true),
                                new GroupAssignment(AUDITORS, "Auditor", false)))
                        .hash())
                .as("the Superuser marker moved to another Group");
    }

    // Validation

    @Test
    void anUnknownPermissionIsRefusedByName() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, new RoleDefinition("Helpdesk", List.of("user:reed"))),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Role 'Helpdesk' names unknown Permission"
                        + " 'user:reed'");
    }

    @Test
    void anUnknownRoleIsRefusedByName() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Helpdesk", false))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Group " + HELPDESK
                        + " is mapped to unknown Role 'Helpdesk'");
    }

    @Test
    void aGroupMappedTwiceIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Helpdesk", false),
                                new GroupAssignment(HELPDESK, "Helpdesk", false))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Group " + HELPDESK
                        + " is mapped more than once");
    }

    /** The first entry for a Group is not silently kept: a duplicate fails even with another Role. */
    @Test
    void aGroupMappedTwiceToDifferentRolesIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, AUDITOR_ROLE),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Helpdesk", false),
                                new GroupAssignment(HELPDESK, "Auditor", false))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessageContaining("Group " + HELPDESK + " is mapped more than once");
    }

    @Test
    void anEntryWithNoGroupIdIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(null, "Helpdesk", false))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: a role mapping entry names no Group id");
    }

    @Test
    void noSuperuserGroupIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER),
                        List.of(new GroupAssignment(ADMINS, "Superuser", false))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: no Superuser Group is designated");
    }

    @Test
    void anEmptyMappingIsRefusedForHavingNoSuperuserGroup() {
        assertThatThrownBy(() -> RoleMapping.of(List.of(), List.of()))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: no Superuser Group is designated");
    }

    @Test
    void moreThanOneSuperuserGroupIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true),
                                new GroupAssignment(HELPDESK, "Superuser", true))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: more than one Superuser Group is designated: "
                        + ADMINS + ", " + HELPDESK);
    }

    /** Missing Permissions are named sorted by name, not in declaration order. */
    @Test
    void aSuperuserRoleMissingAPermissionIsRefusedNamingWhatIsMissing() {
        List<String> allButTwo = EVERY.stream()
                .filter(name -> !name.equals("user:write") && !name.equals("audit:read"))
                .toList();

        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(new RoleDefinition("Superuser", allButTwo)),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Superuser Group " + ADMINS
                        + "'s Role 'Superuser' is missing Permissions: audit:read, user:write");
    }

    @Test
    void aRoleDefinedTwiceIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, HELPDESK_ROLE, HELPDESK_ROLE),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Role 'Helpdesk' is defined more than once");
    }

    @Test
    void aRoleWithNoNameIsRefused() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, new RoleDefinition(" ", List.of("user:read"))),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: a Role has no name");
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(SUPERUSER, new RoleDefinition(null, List.of("user:read"))),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true))))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: a Role has no name");
    }

    /** Every problem at once, so one redeploy fixes them all. */
    @Test
    void everyProblemIsReportedTogether() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(new RoleDefinition("Helpdesk", List.of("user:reed"))),
                        List.of(new GroupAssignment(HELPDESK, "Nobody", false))))
                .isInstanceOfSatisfying(InvalidRoleMappingException.class, invalid ->
                        assertThat(invalid.problems()).containsExactly(
                                "Role 'Helpdesk' names unknown Permission 'user:reed'",
                                "Group " + HELPDESK + " is mapped to unknown Role 'Nobody'",
                                "no Superuser Group is designated"))
                .hasMessage("Invalid role mapping: Role 'Helpdesk' names unknown Permission"
                        + " 'user:reed'; Group " + HELPDESK + " is mapped to unknown Role"
                        + " 'Nobody'; no Superuser Group is designated");
    }

    /** A Superuser entry naming an unknown Role is reported once, as the unknown Role. */
    @Test
    void aSuperuserGroupWithAnUnknownRoleIsReportedAsTheUnknownRole() {
        assertThatThrownBy(() -> RoleMapping.of(
                        List.of(),
                        List.of(new GroupAssignment(ADMINS, "Superuser", true))))
                .isInstanceOfSatisfying(InvalidRoleMappingException.class, invalid ->
                        assertThat(invalid.problems()).containsExactly(
                                "Group " + ADMINS + " is mapped to unknown Role 'Superuser'"));
    }
}
