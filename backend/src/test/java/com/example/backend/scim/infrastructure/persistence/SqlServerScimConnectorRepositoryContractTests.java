package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimConnectorRepositoryContract;
import com.example.backend.scim.domain.ScimConnectorRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production connector port — the JPA adapter over SQL Server — holds to the rules the
 * in-memory store does.
 *
 * <p>It shares the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}). The adapter flushes each save as it makes it, so every read-back
 * is the row's.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimConnectorRepositoryContractTests extends ScimConnectorRepositoryContract {

    @Autowired
    private ScimConnectorRepository connectors;

    @Override
    protected ScimConnectorRepository connectors() {
        return connectors;
    }
}
