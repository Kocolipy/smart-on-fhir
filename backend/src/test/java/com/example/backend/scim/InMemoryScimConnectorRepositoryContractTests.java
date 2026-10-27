package com.example.backend.scim;

import com.example.backend.scim.domain.ScimConnectorRepository;

/** The in-memory connector store holds to the rules the SQL Server adapter does. */
class InMemoryScimConnectorRepositoryContractTests extends ScimConnectorRepositoryContract {

    private final InMemoryScimConnectorRepository connectors = new InMemoryScimConnectorRepository();

    @Override
    protected ScimConnectorRepository connectors() {
        return connectors;
    }
}
