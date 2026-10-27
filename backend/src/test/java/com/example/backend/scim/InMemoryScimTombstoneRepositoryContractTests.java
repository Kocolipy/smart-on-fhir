package com.example.backend.scim;

import com.example.backend.scim.InMemoryScimTombstoneRepository.Tombstone;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import java.util.List;
import java.util.UUID;

/** The in-memory tombstone store holds to the rules the SQL Server adapter does. */
class InMemoryScimTombstoneRepositoryContractTests extends ScimTombstoneRepositoryContract {

    private final InMemoryScimTombstoneRepository tombstones = new InMemoryScimTombstoneRepository();

    @Override
    protected ScimTombstoneRepository tombstones() {
        return tombstones;
    }

    @Override
    protected List<Tombstone> tombstonesOf(UUID id) {
        return tombstones.recorded().stream().filter(tombstone -> tombstone.id().equals(id)).toList();
    }
}
