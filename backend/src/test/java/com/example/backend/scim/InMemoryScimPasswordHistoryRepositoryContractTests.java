package com.example.backend.scim;

import com.example.backend.scim.domain.ScimPasswordHistoryRepository;
import java.util.UUID;

/**
 * The in-memory password history holds to the rules the SQL Server adapter does. It keeps no User,
 * so any id stands for one.
 */
class InMemoryScimPasswordHistoryRepositoryContractTests
        extends ScimPasswordHistoryRepositoryContract {

    private final InMemoryScimPasswordHistoryRepository history =
            new InMemoryScimPasswordHistoryRepository();

    @Override
    protected ScimPasswordHistoryRepository history() {
        return history;
    }

    @Override
    protected UUID givenUser() {
        return UUID.randomUUID();
    }
}
