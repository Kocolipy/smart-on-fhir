package com.example.backend.scim;

import com.example.backend.scim.domain.PasswordHistoryPolicy;
import com.example.backend.scim.domain.ScimPasswordHistoryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A {@link ScimPasswordHistoryRepository} in memory, trimming as the adapter does. */
public final class InMemoryScimPasswordHistoryRepository implements ScimPasswordHistoryRepository {

    /** One remembered password: its hash, and when it was set. */
    private record Entry(String passwordHash, Instant setAt) {
    }

    private final Map<UUID, List<Entry>> newestFirst = new LinkedHashMap<>();

    @Override
    public List<String> findRecentHashes(UUID userId) {
        return entries(userId).stream().map(Entry::passwordHash).toList();
    }

    /** When each retained password was set, newest first — what the adapter stores beside it. */
    public List<Instant> findRecentSetAts(UUID userId) {
        return entries(userId).stream().map(Entry::setAt).toList();
    }

    @Override
    public void record(UUID userId, String passwordHash, Instant setAt) {
        List<Entry> history = newestFirst.computeIfAbsent(userId, key -> new ArrayList<>());
        history.add(0, new Entry(passwordHash, setAt));
        while (history.size() > PasswordHistoryPolicy.RETAINED) {
            history.remove(history.size() - 1);
        }
    }

    private List<Entry> entries(UUID userId) {
        return List.copyOf(newestFirst.getOrDefault(userId, List.of()));
    }
}
