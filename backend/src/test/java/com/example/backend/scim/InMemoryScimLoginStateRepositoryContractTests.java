package com.example.backend.scim;

import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUserRepository;

/** The in-memory User store holds to the login-state rules the SQL Server adapter does. */
class InMemoryScimLoginStateRepositoryContractTests extends ScimLoginStateRepositoryContract {

    private final InMemoryScimUserRepository store = new InMemoryScimUserRepository();

    @Override
    protected ScimLoginStateRepository loginStates() {
        return store;
    }

    @Override
    protected ScimUserRepository users() {
        return store;
    }
}
