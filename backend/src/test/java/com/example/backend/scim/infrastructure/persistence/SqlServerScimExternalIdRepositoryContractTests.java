package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimExternalIdRepositoryContract;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.domain.ScimExternalIdRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production alias port — the JPA adapter over SQL Server — holds to the rules the in-memory
 * store does. Each connector is saved through the connector port and each resource is a User
 * created through the directory port, so every alias written satisfies the table's foreign keys.
 *
 * <p>It shares the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}).
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimExternalIdRepositoryContractTests extends ScimExternalIdRepositoryContract {

    @Autowired
    private ScimExternalIdRepository aliases;

    @Autowired
    private ScimConnectorRepository connectors;

    @Autowired
    private ScimUserRepository users;

    @Override
    protected ScimExternalIdRepository aliases() {
        return aliases;
    }

    @Override
    protected UUID givenConnector() {
        ScimConnector connector =
                ScimConnector.create(UUID.randomUUID(), "Okta", ScimIdentities.NOW);
        connectors.save(connector);
        return connector.id();
    }

    @Override
    protected UUID givenResource() {
        return users.create(ScimIdentities.user("alias-" + UUID.randomUUID())).id();
    }
}
