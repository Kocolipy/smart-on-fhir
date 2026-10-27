package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.ScimPasswordHistoryRepositoryContract;
import com.example.backend.scim.domain.ScimPasswordHistoryRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production password history port — the JPA adapter over SQL Server — holds to the rules the
 * in-memory store does. Each User is created through the directory port, so every entry satisfies
 * the table's foreign key.
 *
 * <p>It shares the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}). The adapter flushes each entry before it trims, so every
 * read-back is the rows'.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimPasswordHistoryRepositoryContractTests
        extends ScimPasswordHistoryRepositoryContract {

    @Autowired
    private ScimPasswordHistoryRepository history;

    @Autowired
    private ScimUserRepository users;

    @Override
    protected ScimPasswordHistoryRepository history() {
        return history;
    }

    @Override
    protected UUID givenUser() {
        return users.create(ScimIdentities.user("history-" + UUID.randomUUID())).id();
    }
}
