package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.InMemoryScimTombstoneRepository.Tombstone;
import com.example.backend.scim.ScimTombstoneRepositoryContract;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The production tombstone port — the JDBC adapter over SQL Server — holds to the rules the
 * in-memory store does.
 *
 * <p>The port has no read, so what the table holds is read straight from it, on the context's own
 * connections and in the case's own transaction, where the adapter's insert is visible. It shares
 * the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries, and with it a
 * database other classes use, so each case runs in a transaction rolled back after it
 * ({@link RollsBackEachCase}): the application login may insert a tombstone but never remove one,
 * so nothing a case writes could be cleaned up after a commit.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimTombstoneRepositoryContractTests extends ScimTombstoneRepositoryContract {

    private static final String TOMBSTONES_OF = """
            SELECT resource_type, resource_id, deleted_at FROM scim_tombstones
             WHERE resource_id = ?""";

    @Autowired
    private ScimTombstoneRepository tombstones;

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    protected ScimTombstoneRepository tombstones() {
        return tombstones;
    }

    @Override
    protected List<Tombstone> tombstonesOf(UUID id) {
        return jdbc.query(TOMBSTONES_OF, (row, number) -> new Tombstone(
                        typeNamed(row.getString("resource_type")),
                        row.getObject("resource_id", UUID.class),
                        row.getObject("deleted_at", OffsetDateTime.class).toInstant()),
                id);
    }

    private static ScimResourceType typeNamed(String name) {
        return Arrays.stream(ScimResourceType.values())
                .filter(type -> type.resourceTypeName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
