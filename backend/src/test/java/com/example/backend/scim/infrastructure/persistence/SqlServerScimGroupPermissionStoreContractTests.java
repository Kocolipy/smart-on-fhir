package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimGroupPermissionStoreContract;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production Group Permissions store — the JDBC adapter over SQL Server — holds to the rule the
 * in-memory store does.
 *
 * <p>It shares the plain cached context {@link SqlServerScimGroupRepositoryContractTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}): nothing a case writes may outlive it. The adapter's statements
 * join that transaction, so every read-back sees the case's own writes.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimGroupPermissionStoreContractTests extends ScimGroupPermissionStoreContract {

    @Autowired
    private ScimGroupPermissionStore store;

    @Autowired
    private ScimGroupRepository groups;

    @Override
    protected ScimGroupPermissionStore store() {
        return store;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }
}
