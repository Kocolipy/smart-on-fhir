package com.example.backend.auth.infrastructure.session;

import com.example.backend.auth.AccountSessionsContract;
import com.example.backend.auth.domain.AccountSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * The production {@link AccountSessions} — the Spring Session adapter over the real, indexed Redis
 * repository — holds to the seam the in-memory double does. {@code AGENTS.md} requires a check
 * against Redis for Redis-backed session persistence: the adapter's own unit test runs against a
 * fake index, which cannot show the real one is searched the way the adapter assumes.
 *
 * <p>Its configuration is the plain one {@link RedisSessionRevocationIntegrationTests} carries, so
 * it shares that cached context and its Redis database ({@code ContainerTestConfiguration}) rather
 * than booting one of its own. Sharing is why every account here is a fresh id and every session
 * it opens is deleted after the test: other classes write the same database.
 */
@SpringBootTest
@Import(com.example.backend.ContainerTestConfiguration.class)
class RedisAccountSessionsContractTests extends AccountSessionsContract {

    @Autowired
    private AccountSessionsAdapter adapter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> repository;

    private final List<String> opened = new ArrayList<>();

    @Override
    protected AccountSessions sessions() {
        return adapter;
    }

    /** Opens a session indexed by the account's stable id, as a real Login would. */
    @Override
    protected String open(UUID accountId) {
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> sessions =
                (FindByIndexNameSessionRepository<Session>) repository;
        Session session = sessions.createSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, accountId.toString());
        sessions.save(session);
        opened.add(session.getId());
        return session.getId();
    }

    @Override
    protected boolean isLive(String sessionId) {
        return repository.findById(sessionId) != null;
    }

    @AfterEach
    void endWhatThisTestOpened() {
        opened.forEach(repository::deleteById);
        opened.clear();
    }
}
