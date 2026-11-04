package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.DuplicateConnectorNameException;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.infrastructure.persistence.entity.ScimConnectorEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** Maps the connector port onto JPA. */
@Repository
class ScimConnectorPersistenceAdapter implements ScimConnectorRepository {

    private final ScimConnectorJpaRepository connectors;

    ScimConnectorPersistenceAdapter(ScimConnectorJpaRepository connectors) {
        this.connectors = connectors;
    }

    /**
     * {@code saveAndFlush} rather than {@code save}: a connector creation and the
     * audit event recording it are one atomic thing, and the audit append flushes at
     * its own call. Deferring this insert to the commit would order the event's
     * insert before the connector's, so a foreign key added later between them would
     * fail at commit rather than here — and so a live name already taken is refused here, as
     * {@link DuplicateConnectorNameException}, where the caller can answer it. Only the
     * live-name index's violation is translated: any other is a fault, and stays one.
     */
    @Override
    public void save(ScimConnector connector) {
        try {
            connectors.saveAndFlush(new ScimConnectorEntity(
                    connector.id(),
                    connector.displayName(),
                    connector.createdAt(),
                    connector.deletedAt()));
        } catch (DataIntegrityViolationException violation) {
            throw IntegrityViolations.translated(violation,
                    IntegrityViolations.CONNECTOR_NAME_UNIQUE,
                    DuplicateConnectorNameException::new);
        }
    }

    @Override
    public Optional<ScimConnector> findById(UUID id) {
        return connectors.findById(id)
                .map(ScimConnectorPersistenceAdapter::toConnector);
    }

    @Override
    public List<ScimConnector> findAllLiveOrderedByDisplayName() {
        return connectors.findByDeletedAtIsNullOrderByDisplayNameAsc().stream()
                .map(ScimConnectorPersistenceAdapter::toConnector)
                .toList();
    }

    private static ScimConnector toConnector(ScimConnectorEntity entity) {
        return new ScimConnector(
                entity.getId(),
                entity.getDisplayName(),
                entity.getCreatedAt(),
                entity.getDeletedAt());
    }
}
