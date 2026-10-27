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

    /**
     * Newest is the latest {@code setAt}, as the adapter orders the rows — not the latest call —
     * so an entry set earlier than one already held goes behind it, and the trim drops whichever
     * was set earliest ({@code ScimPasswordHistoryRepositoryContract}). Two entries set at one
     * instant read newest-recorded first here; the adapter breaks that tie by a random row id.
     */
    @Override
    public void record(UUID userId, String passwordHash, Instant setAt) {
        List<Entry> history = newestFirst.computeIfAbsent(userId, key -> new ArrayList<>());
        int position = 0;
        while (position < history.size() && history.get(position).setAt().isAfter(setAt)) {
            position++;
        }
        history.add(position, new Entry(passwordHash, setAt));
        while (history.size() > PasswordHistoryPolicy.RETAINED) {
            history.remove(history.size() - 1);
        }
    }

    private List<Entry> entries(UUID userId) {
        return List.copyOf(newestFirst.getOrDefault(userId, List.of()));
    }
}
