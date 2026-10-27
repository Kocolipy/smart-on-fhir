package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.InMemoryScimTombstoneRepository.Tombstone;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScimTombstoneRepository} does, whatever stores the tombstones: the SQL Server
 * adapter, and the in-memory store the SCIM deletion unit tests run against. Each runs it.
 *
 * <p>The port is write-only — no use case reads a tombstone back — so each half reads what it
 * holds through {@link #tombstonesOf}: the in-memory store's own record, and the table for SQL
 * Server. Every instant is in whole seconds, so it survives the {@code DATETIMEOFFSET(6)} column
 * unchanged.
 */
public abstract class ScimTombstoneRepositoryContract {

    private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant SECOND = Instant.parse("2026-02-01T00:00:00Z");

    /** The tombstone port under test. */
    protected abstract ScimTombstoneRepository tombstones();

    /** Every tombstone the store holds for this id. */
    protected abstract List<Tombstone> tombstonesOf(UUID id);

    @Test
    void a_users_tombstone_holds_its_type_id_and_deletion() {
        UUID id = UUID.randomUUID();

        tombstones().record(ScimResourceType.USER, id, FIRST);

        assertThat(tombstonesOf(id)).containsExactly(
                new Tombstone(ScimResourceType.USER, id, FIRST));
    }

    @Test
    void a_groups_tombstone_holds_its_type_id_and_deletion() {
        UUID id = UUID.randomUUID();

        tombstones().record(ScimResourceType.GROUP, id, FIRST);

        assertThat(tombstonesOf(id)).containsExactly(
                new Tombstone(ScimResourceType.GROUP, id, FIRST));
    }

    /**
     * One tombstone per id, ever: the first deletion's. A fixture Group the seed creates again
     * under its fixed id and a connector deletes a second time keeps the tombstone already
     * standing rather than colliding on it.
     */
    @Test
    void a_second_deletion_of_one_id_keeps_the_first_tombstone() {
        UUID id = UUID.randomUUID();
        tombstones().record(ScimResourceType.GROUP, id, FIRST);

        tombstones().record(ScimResourceType.GROUP, id, SECOND);

        assertThat(tombstonesOf(id)).containsExactly(
                new Tombstone(ScimResourceType.GROUP, id, FIRST));
    }

    @Test
    void an_id_never_deleted_has_no_tombstone() {
        tombstones().record(ScimResourceType.USER, UUID.randomUUID(), FIRST);

        assertThat(tombstonesOf(UUID.randomUUID())).isEmpty();
    }
}
