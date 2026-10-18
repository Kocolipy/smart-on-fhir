package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimLoginStateRepositoryContract;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production login-state port — the JPA adapter over SQL Server — holds to the rules the
 * in-memory store does.
 *
 * <p>Its configuration is the plain one {@link ScimUserPersistenceAdapterIntegrationTests}
 * carries, so it shares that cached context and its database rather than booting one of its own.
 * Sharing is why each case runs in a transaction rolled back after it ({@link RollsBackEachCase}):
 * the dormancy case deletes the seeded Bootstrap Admin to hold the reservation itself, and nothing
 * a case writes may outlive it. The contract asks nothing of a commit — the adapter's statements
 * flush and clear as they run, so every read-back is the row's — and what does depend on
 * transactions stays in {@link ScimUserPersistenceAdapterIntegrationTests}.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimLoginStateRepositoryContractTests extends ScimLoginStateRepositoryContract {

    @Autowired
    private ScimLoginStateRepository loginStates;

    @Autowired
    private ScimUserRepository users;

    @Override
    protected ScimLoginStateRepository loginStates() {
        return loginStates;
    }

    @Override
    protected ScimUserRepository users() {
        return users;
    }
}
