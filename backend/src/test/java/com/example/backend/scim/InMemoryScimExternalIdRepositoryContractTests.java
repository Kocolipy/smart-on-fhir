package com.example.backend.scim;

import com.example.backend.scim.domain.ScimExternalIdRepository;
import java.util.UUID;

/**
 * The in-memory alias store holds to the rules the SQL Server adapter does. It keeps no connector
 * or resource, so any id stands for one.
 */
class InMemoryScimExternalIdRepositoryContractTests extends ScimExternalIdRepositoryContract {

    private final InMemoryScimExternalIdRepository aliases = new InMemoryScimExternalIdRepository();

    @Override
    protected ScimExternalIdRepository aliases() {
        return aliases;
    }

    @Override
    protected UUID givenConnector() {
        return UUID.randomUUID();
    }

    @Override
    protected UUID givenResource() {
        return UUID.randomUUID();
    }
}
