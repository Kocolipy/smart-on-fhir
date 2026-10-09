package com.example.backend.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

/**
 * The adapter over a session repository, which is the only contract it depends on — the real one
 * is Redis-backed, and {@code EpicTokensIntegrationTests} holds it to the store. What is pinned
 * here is what it answers: the tokens a live session holds, and nothing for a session that holds
 * none, does not exist, or has outlived the absolute session lifetime.
 */
class EpicTokensAdapterTests {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);

    private final Map<String, org.springframework.session.Session> store = new ConcurrentHashMap<>();

    private final MapSessionRepository sessions = new MapSessionRepository(store);

    private final EpicTokens adapter = new EpicTokensAdapter(sessions,
            new AbsoluteSessionLifetimePolicy(ABSOLUTE_LIFETIME), Clock.fixed(NOW, ZoneOffset.UTC));

    private static final EpicTokenSet TOKENS = EpicTokenSet.issued("access-token-value",
            Duration.ofHours(1), NOW, Set.of("openid"), Optional.empty(), "id-token-value");

    @Test
    void aSessionHoldingEpicTokensAnswersWithThem() {
        String id = open(NOW.minus(Duration.ofMinutes(5)), TOKENS);

        assertThat(adapter.forSession(id)).containsSame(TOKENS);
    }

    /** A password Login's session: signed in, but nothing of Epic's in it. */
    @Test
    void aSessionHoldingNoEpicTokensAnswersEmpty() {
        String id = open(NOW.minus(Duration.ofMinutes(5)), null);

        assertThat(adapter.forSession(id)).isEmpty();
    }

    @Test
    void anUnknownSessionAnswersEmpty() {
        assertThat(adapter.forSession("no-such-session")).isEmpty();
    }

    @Test
    void noSessionIdAnswersEmpty() {
        assertThat(adapter.forSession(null)).isEmpty();
    }

    /**
     * A session past its absolute lifetime has ended, even while the store still holds it until
     * its next request or its idle bound reaps it: its tokens go with it.
     */
    @Test
    void aSessionPastItsAbsoluteLifetimeAnswersEmpty() {
        String id = open(NOW.minus(ABSOLUTE_LIFETIME).minusSeconds(1), TOKENS);

        assertThat(adapter.forSession(id)).isEmpty();
    }

    /** At the boundary the session is still within its lifetime, as the policy has it. */
    @Test
    void aSessionExactlyAtItsAbsoluteLifetimeStillAnswers() {
        String id = open(NOW.minus(ABSOLUTE_LIFETIME), TOKENS);

        assertThat(adapter.forSession(id)).containsSame(TOKENS);
    }

    /** Something else under the attribute's name is not a token set, and is not handed out. */
    @Test
    void anAttributeOfAnotherTypeAnswersEmpty() {
        MapSession session = new MapSession();
        session.setCreationTime(NOW);
        session.setLastAccessedTime(NOW);
        session.setMaxInactiveInterval(Duration.ofDays(365_000));
        session.setAttribute(EpicTokens.SESSION_ATTRIBUTE, "not a token set");
        sessions.save(session);

        assertThat(adapter.forSession(session.getId())).isEmpty();
    }

    /**
     * A session created at {@code createdAt}, holding {@code tokens} when there are any. Its idle
     * bound is set far beyond any test, because the map repository measures it on the system
     * clock and this test is about the absolute lifetime alone.
     */
    private String open(Instant createdAt, EpicTokenSet tokens) {
        MapSession session = new MapSession();
        session.setCreationTime(createdAt);
        session.setLastAccessedTime(Instant.now());
        session.setMaxInactiveInterval(Duration.ofDays(365_000));
        if (tokens != null) {
            session.setAttribute(EpicTokens.SESSION_ATTRIBUTE, tokens);
        }
        sessions.save(session);
        return session.getId();
    }
}
