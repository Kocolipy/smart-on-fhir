package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.scim.GroupPermissionsContract;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The production Group Permissions — the JDBC adapter over SQL Server — answer which Users are the
 * Superuser, and what their Groups carry, as the in-memory adapter does.
 *
 * <p>It shares the plain cached context {@link SqlServerScimGroupRepositoryContractTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}): a case that deletes the seeded Admin group to reserve its own
 * leaves it standing for the next.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerGroupPermissionsContractTests extends GroupPermissionsContract {

    @Autowired
    private GroupPermissions permissions;

    @Autowired
    private ScimGroupPermissionStore store;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private ScimUserRepository users;

    @Override
    protected GroupPermissions permissions() {
        return permissions;
    }

    @Override
    protected ScimGroupPermissionStore store() {
        return store;
    }

    @Override
    protected ScimGroupRepository groups() {
        return groups;
    }

    @Override
    protected ScimUserRepository users() {
        return users;
    }
}
