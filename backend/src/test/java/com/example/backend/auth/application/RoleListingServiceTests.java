package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The read-only view of the role mapping: every Role by name, its Permissions by name, and the
 * Groups that confer it, named by the directory and with the Superuser Group marked.
 */
class RoleListingServiceTests {

    private static final UUID SUPERUSERS = TestRoleMappings.SUPERUSER_GROUP_ID;
    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000c0002");
    private static final UUID SERVICE_DESK =
            UUID.fromString("00000000-0000-4000-8000-0000000c0003");
    private static final UUID RETIRED = UUID.fromString("00000000-0000-4000-8000-0000000c0004");

    private final InMemoryScimGroupRepository groups =
            new InMemoryScimGroupRepository(new InMemoryScimUserRepository());

    private final RoleMapping mapping = RoleMapping.of(
            List.of(new RoleDefinition("Superuser", TestRoleMappings.EVERY_PERMISSION),
                    new RoleDefinition("Helpdesk", List.of("user:read", "group:read", "account:unlock")),
                    new RoleDefinition("Auditor", List.of("audit:read")),
                    new RoleDefinition("Unassigned", List.of())),
            List.of(new GroupAssignment(SUPERUSERS, "Superuser", true),
                    new GroupAssignment(SERVICE_DESK, "Helpdesk", false),
                    new GroupAssignment(HELPDESK, "Helpdesk", false),
                    new GroupAssignment(RETIRED, "Auditor", false)));

    private final RoleListingService service = new RoleListingService(mapping, groups);

    @Test
    void listsEveryRoleByNameWithItsPermissionsAndTheGroupsConferringIt() {
        stored(SUPERUSERS, "Admins");
        stored(HELPDESK, "Helpdesk");
        stored(SERVICE_DESK, "Service desk");

        List<RoleSummary> roles = service.listRoles();

        assertThat(roles).extracting(RoleSummary::name)
                .containsExactly("Auditor", "Helpdesk", "Superuser", "Unassigned");
        assertThat(roles.get(1).permissions())
                .as("sorted by name, whatever order configuration gave")
                .containsExactly("account:unlock", "group:read", "user:read");
        assertThat(roles.get(1).groups()).as("in mapping order").containsExactly(
                new RoleSummary.MappedGroup(SERVICE_DESK, "Service desk", false),
                new RoleSummary.MappedGroup(HELPDESK, "Helpdesk", false));
        assertThat(roles.get(2).groups()).containsExactly(
                new RoleSummary.MappedGroup(SUPERUSERS, "Admins", true));
        assertThat(roles.get(2).permissions()).hasSize(TestRoleMappings.EVERY_PERMISSION.size());
        assertThat(roles.get(3).groups()).as("a Role no Group confers").isEmpty();
        assertThat(roles.get(3).permissions()).isEmpty();
    }

    /**
     * A mapped Group deleted over SCIM is still named by the mapping, by id, but no live Group
     * has a name for it.
     */
    @Test
    void aMappedGroupWithNoLiveGroupIsListedByIdWithNoName() {
        stored(SUPERUSERS, "Admins");

        RoleSummary auditor = service.listRoles().getFirst();

        assertThat(auditor.groups()).containsExactly(
                new RoleSummary.MappedGroup(RETIRED, null, false));
    }

    private void stored(UUID id, String displayName) {
        groups.create(ScimGroup.created(id, displayName, List.of(), ScimIdentities.NOW));
    }
}
