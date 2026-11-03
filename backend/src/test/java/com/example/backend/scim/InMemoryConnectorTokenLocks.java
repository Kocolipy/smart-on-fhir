package com.example.backend.scim;

import com.example.backend.scim.domain.ConnectorTokenLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * No lock to take in memory: a single-threaded test has no second writer to exclude. Each request
 * is recorded, so a test can assert a write asked to lock the token it addresses;
 * {@code ConnectorTokenRotationRaceIntegrationTests} holds what the lock does on SQL Server.
 *
 * <p>Nor does it require a transaction, which the real adapter refuses to lock without
 * ({@link ConnectorTokenLocks}): the services under test here run with no transaction manager at
 * all, and without a second writer there is no lock for one to keep. Whether every production
 * caller runs in a transaction is the real adapter's to refuse
 * ({@code ScimWriteLockAdapterIntegrationTests}).
 */
public final class InMemoryConnectorTokenLocks implements ConnectorTokenLocks {

    private final List<UUID> requests = new ArrayList<>();

    @Override
    public void lockToken(UUID tokenId) {
        requests.add(tokenId);
    }

    /** Every token a lock was asked for, in call order. */
    public List<UUID> requests() {
        return List.copyOf(requests);
    }
}
