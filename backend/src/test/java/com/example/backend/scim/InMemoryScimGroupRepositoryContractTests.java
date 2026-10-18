package com.example.backend.scim;

import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;

/** The in-memory Group store holds to the directory rules the SQL Server adapter does. */
class InMemoryScimGroupRepositoryContractTests extends ScimGroupRepositoryContract {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }

    @Override
    protected ScimUserRepository users() {
        return users;
    }
}
