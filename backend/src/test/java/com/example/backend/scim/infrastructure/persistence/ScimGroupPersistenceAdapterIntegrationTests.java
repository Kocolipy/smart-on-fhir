package com.example.backend.scim.infrastructure.persistence;

import static com.example.backend.ScimTestRows.unique;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Group store against a real SQL Server, when a Group's deletion commits while a read of it is
 * running. Each read must see the Group whole or not at all, never a Group row whose resource has
 * gone.
 *
 * <p>The Groups here have no members. A Group's members are read in a statement of their own,
 * after the Group, so a Group deleted between the two is read with none. That is a stale read
 * rather than a failure, and asserting on it is not the point of these tests.
 *
 * <p>What the port promises within one transaction is {@code ScimGroupRepositoryContract}'s, which
 * {@link SqlServerScimGroupRepositoryContractTests} runs against this adapter; what depends on a
 * second transaction committing stays here.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimGroupPersistenceAdapterIntegrationTests {

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        created.forEach(this::deleteCommitted);
    }

    /** A page of Groups by id, as a SCIM query loads one, reads each Group whole or not at all. */
    @Test
    void aGroupDeletedDuringAPageReadIsReadWholeOrNotAtAll() {
        ScimGroup victim = stored(unique("adapter-group-page"));

        List<ScimGroup> page = inTransaction(() -> CommitBetweenStatements.afterReading(
                "scim_groups",
                () -> deleteCommitted(victim.id()),
                () -> groups.findAllById(List.of(victim.id()))));

        assertThat(page).isIn(List.of(), List.of(victim));
    }

    // ---- helpers --------------------------------------------------------------------------

    /** A stored Group with no members, read back as the store holds it. */
    private ScimGroup stored(String displayName) {
        ScimGroup group = inTransaction(() -> groups.create(ScimIdentities.group(displayName)));
        created.add(group.id());
        return inTransaction(() -> groups.findById(group.id()).orElseThrow());
    }

    /** Deletes the Group in a transaction of its own, which commits before this returns. */
    private void deleteCommitted(UUID id) {
        inTransaction(() -> groups.deleteById(id, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

}
