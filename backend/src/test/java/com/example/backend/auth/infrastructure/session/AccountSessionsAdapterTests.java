package com.example.backend.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.example.backend.audit.CapturedLog;
import com.example.backend.auth.AccountSessionsContract;
import com.example.backend.auth.domain.AccountSessions;
import com.example.backend.observability.LogEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

/**
 * The adapter over a session repository that indexes by principal, which is the only contract it
 * depends on — the real one is Redis-backed ({@link RedisAccountSessionsContractTests}), and none
 * of what this class does is Redis-specific.
 *
 * <p>The seam itself is {@link AccountSessionsContract}'s: that the adapter deletes every session
 * it found rather than the first, deletes nobody else's, and treats an account signed in nowhere
 * as a no-op rather than an error.
 *
 * <p>The fake indexes sessions by the same value {@link AccountSessionsAdapter} writes and
 * searches: the account's stable id, stringified.
 */
class AccountSessionsAdapterTests extends AccountSessionsContract {

    private final IndexedSessions sessions = new IndexedSessions();

    private final AccountSessionsAdapter adapter = new AccountSessionsAdapter(sessions);

    @Override
    protected AccountSessions sessions() {
        return adapter;
    }

    @Override
    protected String open(UUID accountId) {
        return sessions.open(accountId);
    }

    @Override
    protected boolean isLive(String sessionId) {
        return sessions.findById(sessionId) != null;
    }

    /**
     * The {@code session-end} record is the Session revocation module's, written under the
     * revocation's cause — which this adapter is not told. A record here would be a second one,
     * naming no cause.
     */
    @Test
    void endingSessionsWritesNoRecordOfItsOwn() {
        UUID bob = UUID.randomUUID();
        sessions.open(bob);
        String retained = sessions.open(bob);
        sessions.open(bob);

        try (CapturedLog captured = CapturedLog.attach()) {
            adapter.revokeAllExcept(bob, retained);
            adapter.revokeAll(bob);

            assertThat(captured.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
        }
    }

    /** A session store that can be searched by principal, and nothing more. */
    private static final class IndexedSessions
            implements FindByIndexNameSessionRepository<MapSession> {

        private final Map<String, MapSession> stored = new LinkedHashMap<>();

        /** Register a session for this account, as an accepted login would. */
        String open(UUID accountId) {
            MapSession session = createSession();
            session.setAttribute(PRINCIPAL_NAME_INDEX_NAME, accountId.toString());
            save(session);
            return session.getId();
        }

        @Override
        public Map<String, MapSession> findByIndexNameAndIndexValue(
                String indexName, String indexValue) {
            if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
                return Map.of();
            }
            return stored.values().stream()
                    .filter(session -> indexValue.equals(
                            session.getAttribute(PRINCIPAL_NAME_INDEX_NAME)))
                    .collect(Collectors.toMap(MapSession::getId, session -> session));
        }

        @Override
        public MapSession createSession() {
            return new MapSession();
        }

        @Override
        public void save(MapSession session) {
            stored.put(session.getId(), session);
        }

        @Override
        public MapSession findById(String id) {
            return stored.get(id);
        }

        @Override
        public void deleteById(String id) {
            stored.remove(id);
        }
    }
}
