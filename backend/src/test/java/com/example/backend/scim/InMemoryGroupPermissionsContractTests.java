package com.example.backend.scim;

import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;

/** The in-memory Group Permissions answer which Users are the Superuser as SQL Server does. */
class InMemoryGroupPermissionsContractTests extends GroupPermissionsContract {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryGroupPermissions permissions = InMemoryGroupPermissions.over(groups);

    @Override
    protected GroupPermissions permissions() {
        return permissions;
    }

    @Override
    protected ScimGroupPermissionStore store() {
        return permissions;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }

    @Override
    protected ScimUserRepository users() {
        return users;
    }
}
