package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimQueryRepositoryContract;
import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production query ports — the JDBC adapter over SQL Server — answer the queries the
 * in-memory store evaluates as it does.
 *
 * <p>It shares the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}); the query reads on the same connection, so it sees what the case
 * wrote.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimQueryRepositoryContractTests extends ScimQueryRepositoryContract {

    @Autowired
    private ScimQueryRepository connectorQueries;

    @Autowired
    private AdministrativeQueryRepository administrativeQueries;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Override
    protected ScimQueryRepository connectorQueries() {
        return connectorQueries;
    }

    @Override
    protected AdministrativeQueryRepository administrativeQueries() {
        return administrativeQueries;
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
