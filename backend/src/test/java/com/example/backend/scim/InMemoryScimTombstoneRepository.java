package com.example.backend.scim;

import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A {@link ScimTombstoneRepository} that keeps what it was told, for use-case unit tests: the
 * first tombstone of each id, as the adapter keeps.
 */
public final class InMemoryScimTombstoneRepository implements ScimTombstoneRepository {

    /** One recorded tombstone. */
    public record Tombstone(ScimResourceType type, UUID id, Instant deletedAt) {
    }

    private final List<Tombstone> recorded = new ArrayList<>();

    /**
     * One tombstone per id, ever: a second deletion of an id keeps the first one's, as the
     * adapter's insert does ({@code ScimTombstoneRepositoryContract}).
     */
    @Override
    public void record(ScimResourceType type, UUID id, Instant deletedAt) {
        if (recorded.stream().noneMatch(tombstone -> tombstone.id().equals(id))) {
            recorded.add(new Tombstone(type, id, deletedAt));
        }
    }

    public List<Tombstone> recorded() {
        return List.copyOf(recorded);
    }
}
