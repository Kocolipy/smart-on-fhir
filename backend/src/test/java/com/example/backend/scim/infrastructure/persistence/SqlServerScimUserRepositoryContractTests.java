package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimUserRepositoryContract;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production directory port — the JPA adapter over SQL Server — holds to the rules the
 * in-memory store does.
 *
 * <p>It shares the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries,
 * and with it a database other classes use. So each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}): the reservation cases delete the seeded Bootstrap Admin to claim
 * its reservation, and nothing a case writes may outlive it. The contract asks nothing of a
 * commit. The adapter flushes each write as it makes it, and its version statements clear the
 * persistence context, so every read-back is the row's.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimUserRepositoryContractTests extends ScimUserRepositoryContract {

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Override
    protected ScimUserRepository users() {
        return users;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }
}
