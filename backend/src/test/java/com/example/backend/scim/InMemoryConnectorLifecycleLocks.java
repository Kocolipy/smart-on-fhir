package com.example.backend.scim;

import com.example.backend.scim.domain.ConnectorLifecycleLocks;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorToken;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The connector lifecycle's locked reads against the in-memory stores. No lock to take in memory:
 * a single-threaded test has no second writer to exclude, so each answer is the store's read.
 * Each request is recorded, so a test can assert which rows a write asked to hold, in which order;
 * {@code ConnectorLifecycleRaceIntegrationTests} and
 * {@code ConnectorTokenRotationRaceIntegrationTests} hold what the locks do on SQL Server.
 *
 * <p>The order is held here as the real adapter's callers must keep it: a token is locked only as
 * one of a connector this module locked, and a token lock naming any other connector is refused.
 *
 * <p>Nor does it require a transaction, which the real adapter refuses to lock without
 * ({@link ConnectorLifecycleLocks}): the services under test here run with no transaction manager
 * at all, and without a second writer there is no lock for one to keep. Whether every production
 * caller runs in a transaction is the real adapter's to refuse
 * ({@code ScimWriteLockAdapterIntegrationTests}).
 */
public final class InMemoryConnectorLifecycleLocks implements ConnectorLifecycleLocks {

    /** One lock asked for: which table's row, and its id. */
    public record Request(String row, UUID id) {

        public static Request connector(UUID id) {
            return new Request("connector", id);
        }

        public static Request token(UUID id) {
            return new Request("token", id);
        }
    }

    private final InMemoryScimConnectorRepository connectors;
    private final InMemoryScimConnectorTokenRepository tokens;
    private final List<Request> requests = new ArrayList<>();
    private final Set<UUID> lockedConnectors = new HashSet<>();

    public InMemoryConnectorLifecycleLocks(
            InMemoryScimConnectorRepository connectors,
            InMemoryScimConnectorTokenRepository tokens) {
        this.connectors = connectors;
        this.tokens = tokens;
    }

    @Override
    public Optional<ScimConnector> lockLiveConnector(UUID connectorId) {
        requests.add(Request.connector(connectorId));
        lockedConnectors.add(connectorId);
        return connectors.findById(connectorId).filter(connector -> !connector.isDeleted());
    }

    @Override
    public Optional<ScimConnectorToken> lockTokenOf(ScimConnector connector, UUID tokenId) {
        if (!lockedConnectors.contains(connector.id())) {
            throw new IllegalStateException(
                    "A token is locked only as one of a connector locked before it");
        }
        requests.add(Request.token(tokenId));
        return tokens.findById(tokenId)
                .filter(token -> token.connectorId().equals(connector.id()));
    }

    /** Every lock asked for since the last {@link #reset}, in call order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Forgets every request so far, so a test sees only the write it acts. */
    public void reset() {
        requests.clear();
    }
}
