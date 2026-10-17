package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.infrastructure.persistence.entity.ScimConnectorTokenEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to the connector token table. */
interface ScimConnectorTokenJpaRepository
        extends JpaRepository<ScimConnectorTokenEntity, UUID> {

    Optional<ScimConnectorTokenEntity> findByLookupId(String lookupId);

    List<ScimConnectorTokenEntity> findByConnectorIdOrderByIssuedAtDesc(UUID connectorId);

    /**
     * Locks this token's row for update ({@code WITH (UPDLOCK, ROWLOCK)}, ADR 0013) until the
     * transaction ends, and reports whether it exists.
     *
     * <p>Called only by {@link ScimWriteLockAdapter}, the one place the scim slice takes a lock;
     * {@code ArchitectureTest} holds that. Native and returning the id alone, so it loads no
     * entity: the read after it is the transaction's first of the token, and sees what the
     * previous lock holder committed — a rotation that waited here then finds the token replaced.
     */
    @Query(value = "SELECT id FROM scim_connector_tokens WITH (UPDLOCK, ROWLOCK) WHERE id = :id",
            nativeQuery = true)
    List<UUID> lockById(@Param("id") UUID id);

    /**
     * Revokes every not-yet-revoked token of one connector in one statement, and
     * returns how many rows that was.
     *
     * <p>Bound parameters, never concatenation — and written as a text block rather
     * than two joined literals, so the query is one literal and cannot be mistaken
     * (by a reader or by {@code be-jpql-string-concatenation}) for one assembled at
     * run time. {@code clearAutomatically} so a token already loaded in this
     * transaction's persistence context is not read back stale after the bulk update
     * went round it, and {@code flushAutomatically} so a pending insert in the same
     * transaction is written before the update decides which rows to touch.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ScimConnectorTokenEntity t SET t.revokedAt = :revokedAt
            WHERE t.connectorId = :connectorId AND t.revokedAt IS NULL
            """)
    int revokeAllForConnector(
            @Param("connectorId") UUID connectorId, @Param("revokedAt") Instant revokedAt);
}
