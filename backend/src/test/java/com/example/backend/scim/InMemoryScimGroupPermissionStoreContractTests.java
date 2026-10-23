package com.example.backend.scim;

import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;

/** The in-memory Group Permissions store holds to the rule the SQL Server adapter does. */
class InMemoryScimGroupPermissionStoreContractTests extends ScimGroupPermissionStoreContract {

    private final InMemoryScimGroupRepository groups =
            new InMemoryScimGroupRepository(new InMemoryScimUserRepository());

    private final InMemoryGroupPermissions store = InMemoryGroupPermissions.over(groups);

    @Override
    protected ScimGroupPermissionStore store() {
        return store;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }
}
