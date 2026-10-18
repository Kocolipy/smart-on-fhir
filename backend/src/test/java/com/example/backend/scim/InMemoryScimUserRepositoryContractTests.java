package com.example.backend.scim;

import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;

/** The in-memory User store holds to the directory rules the SQL Server adapter does. */
class InMemoryScimUserRepositoryContractTests extends ScimUserRepositoryContract {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    @Override
    protected ScimUserRepository users() {
        return users;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }
}
