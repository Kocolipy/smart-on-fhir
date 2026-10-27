package com.example.backend.scim;

import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;

/** The in-memory connector token store holds to the rules the SQL Server adapter does. */
class InMemoryScimConnectorTokenRepositoryContractTests
        extends ScimConnectorTokenRepositoryContract {

    private final InMemoryScimConnectorTokenRepository tokens =
            new InMemoryScimConnectorTokenRepository();

    private final InMemoryScimConnectorRepository connectors = new InMemoryScimConnectorRepository();

    @Override
    protected ScimConnectorTokenRepository tokens() {
        return tokens;
    }

    @Override
    protected ScimConnectorRepository connectors() {
        return connectors;
    }
}
