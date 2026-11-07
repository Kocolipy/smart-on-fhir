package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.infrastructure.persistence.entity.ScimConnectorEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to the connector table. */
interface ScimConnectorJpaRepository extends JpaRepository<ScimConnectorEntity, UUID> {

    /**
     * Live connectors only, alphabetically. {@code deletedAt IS NULL} is the whole
     * definition of live, expressed as a derived query so no caller can forget the
     * predicate.
     */
    List<ScimConnectorEntity> findByDeletedAtIsNullOrderByDisplayNameAsc();

    /**
     * Locks this connector's row for update ({@code WITH (UPDLOCK, ROWLOCK)}, ADR 0013) until the
     * transaction ends, and reports whether it exists.
     *
     * <p>Called only by {@link ScimWriteLockAdapter}, the one place the scim slice takes a lock;
     * {@code ArchitectureTest} holds that. Native and returning the id alone, so it loads no
     * entity: the read after it is the transaction's first of the connector, and sees what the
     * previous lock holder committed — a delete that waited here then finds the connector deleted.
     */
    @Query(value = "SELECT id FROM scim_connectors WITH (UPDLOCK, ROWLOCK) WHERE id = :id",
            nativeQuery = true)
    List<UUID> lockById(@Param("id") UUID id);
}
