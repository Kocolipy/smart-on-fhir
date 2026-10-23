package com.example.backend.scim;

import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * No lock to take in memory: a single-threaded test has no second writer to exclude, so there is
 * no order to keep either — {@link ScimWriteLocks#ORDER} governs only locks a second writer could
 * contend for, and the real adapter takes them in it
 * ({@code ScimWriteLockAdapterIntegrationTests}). Each request is recorded, so a test can assert
 * a writer asked
 * for the rows it promises to lock — the subject and the named resources, not the related rows
 * only the real module reads. That a granted lock actually excludes a second writer is the real
 * adapter's, proven only against a database ({@code ScimWriteLockAdapterIntegrationTests},
 * {@code LoginStateSerializationIntegrationTests}).
 *
 * <p>One interleaving can still be staged on a single thread: a second writer that held the lock
 * and committed just before the next request was granted ({@link #beforeNextGrant}) — what a
 * caller that read the User before asking for its lock must decide past.
 */
public final class InMemoryScimWriteLocks implements ScimWriteLocks {

    private final List<Set<UUID>> requests = new ArrayList<>();

    private Runnable beforeNextGrant;

    /**
     * Runs {@code writer} once, as the next request is granted and before its caller goes on: a
     * second writer that held the lock commits what it wrote, and only then is the lock granted.
     */
    public void beforeNextGrant(Runnable writer) {
        this.beforeNextGrant = writer;
    }

    @Override
    public void lockUserWrite(UUID userId) {
        granted(Set.of(userId));
    }

    @Override
    public void lockGroupWrite(UUID groupId, Collection<UUID> namedUsers) {
        Set<UUID> requested = new LinkedHashSet<>();
        requested.add(groupId);
        requested.addAll(namedUsers);
        granted(Set.copyOf(requested));
    }

    @Override
    public void lock(Collection<UUID> resourceIds) {
        granted(Set.copyOf(resourceIds));
    }

    private void granted(Set<UUID> requested) {
        requests.add(requested);
        Runnable writer = beforeNextGrant;
        beforeNextGrant = null;
        if (writer != null) {
            writer.run();
        }
    }

    /** Every request, in call order, each as the set of ids it named. */
    public List<Set<UUID>> requests() {
        return List.copyOf(requests);
    }
}
