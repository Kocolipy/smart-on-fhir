package com.example.backend.auth;

import com.example.backend.auth.domain.AccountSessions;
import java.util.UUID;

/** The in-memory double holds to the seam the Redis adapter does. */
class InMemoryAccountSessionsContractTests extends AccountSessionsContract {

    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();

    @Override
    protected AccountSessions sessions() {
        return sessions;
    }

    @Override
    protected String open(UUID accountId) {
        String id = UUID.randomUUID().toString();
        sessions.open(accountId, id);
        return id;
    }

    @Override
    protected boolean isLive(String sessionId) {
        return sessions.isLive(sessionId);
    }
}
