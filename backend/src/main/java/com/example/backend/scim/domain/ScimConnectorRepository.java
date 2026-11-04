package com.example.backend.scim.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for connector identity. */
public interface ScimConnectorRepository {

    /**
     * Writes a new connector or replaces an existing one wholly.
     *
     * @throws DuplicateConnectorNameException when the connector is live and another live
     *         connector already holds exactly its display name; a deleted connector holds none
     */
    void save(ScimConnector connector);

    /**
     * The connector with this id, deleted or not.
     *
     * <p>Deleted connectors are returned because the caller's question differs by
     * caller: administration needs to tell "no such connector" from "already
     * deleted", and authentication needs to refuse a deleted one — neither is
     * served by a lookup that pretends the row is gone.
     */
    Optional<ScimConnector> findById(UUID id);

    /** Every live connector, for the Admin listing. Deleted ones are omitted. */
    List<ScimConnector> findAllLiveOrderedByDisplayName();
}
