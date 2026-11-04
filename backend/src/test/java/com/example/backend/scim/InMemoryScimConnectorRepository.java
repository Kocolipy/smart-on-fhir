package com.example.backend.scim;

import com.example.backend.scim.domain.DuplicateConnectorNameException;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link ScimConnectorRepository} in memory, so a use-case test needs no database. Refuses a
 * second live connector under a live one's exact name, as the database's filtered unique index
 * does.
 */
public final class InMemoryScimConnectorRepository implements ScimConnectorRepository {

    private final Map<UUID, ScimConnector> connectors = new LinkedHashMap<>();

    @Override
    public void save(ScimConnector connector) {
        boolean taken = !connector.isDeleted() && connectors.values().stream()
                .anyMatch(held -> !held.isDeleted()
                        && !held.id().equals(connector.id())
                        && held.displayName().equals(connector.displayName()));
        if (taken) {
            throw new DuplicateConnectorNameException(null);
        }
        connectors.put(connector.id(), connector);
    }

    @Override
    public Optional<ScimConnector> findById(UUID id) {
        return Optional.ofNullable(connectors.get(id));
    }

    @Override
    public List<ScimConnector> findAllLiveOrderedByDisplayName() {
        return connectors.values().stream()
                .filter(connector -> !connector.isDeleted())
                .sorted(Comparator.comparing(ScimConnector::displayName))
                .toList();
    }

    /** Seeds a live connector, returning it. */
    public ScimConnector seed(String displayName, Instant createdAt) {
        ScimConnector connector =
                ScimConnector.create(UUID.randomUUID(), displayName, createdAt);
        save(connector);
        return connector;
    }
}
