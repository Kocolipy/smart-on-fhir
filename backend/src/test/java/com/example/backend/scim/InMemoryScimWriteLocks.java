package com.example.backend.scim;

import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * No lock to take in memory: a single-threaded test has no second writer to exclude, and the
 * canonical order is the database's. Each request is recorded, so a test can assert a writer asked
 * for the rows it promises to lock — the subject and the named resources, not the related rows
 * only the real module reads. That a granted lock actually excludes a second writer is the real
 * adapter's, proven only against a database ({@code ScimWriteLockAdapterIntegrationTests},
 * {@code LoginStateSerializationIntegrationTests}).
 */
public final class InMemoryScimWriteLocks implements ScimWriteLocks {

    private final List<Set<UUID>> requests = new ArrayList<>();

    @Override
    public void lockUserWrite(UUID userId) {
        requests.add(Set.of(userId));
    }

    @Override
    public void lockGroupWrite(UUID groupId, Collection<UUID> namedUsers) {
        Set<UUID> requested = new LinkedHashSet<>();
        requested.add(groupId);
        requested.addAll(namedUsers);
        requests.add(Set.copyOf(requested));
    }

    @Override
    public void lock(Collection<UUID> resourceIds) {
        requests.add(Set.copyOf(resourceIds));
    }

    /** Every request, in call order, each as the set of ids it named. */
    public List<Set<UUID>> requests() {
        return List.copyOf(requests);
    }
}
