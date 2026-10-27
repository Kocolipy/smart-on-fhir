package com.example.backend.scim;

import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimUserRepository;

/** The in-memory query store answers the queries it evaluates as the SQL Server adapter does. */
class InMemoryScimQueryRepositoryContractTests extends ScimQueryRepositoryContract {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryScimQueryRepository queries = new InMemoryScimQueryRepository(users, groups);

    @Override
    protected ScimQueryRepository connectorQueries() {
        return queries;
    }

    @Override
    protected AdministrativeQueryRepository administrativeQueries() {
        return queries;
    }

    @Override
    protected ScimUserRepository users() {
        return users;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }
}
