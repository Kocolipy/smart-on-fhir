package com.example.backend.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.domain.EpicTokens.HeldForFhircast;
import com.example.backend.auth.domain.FhircastHub;
import com.example.backend.auth.domain.SignedInSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

/**
 * The adapter over a session repository, which is the only contract it depends on — the real one
 * is Redis-backed, and {@code EpicTokensIntegrationTests} holds it to the store. What is pinned
 * here is what it answers: the tokens a live session holds, and for the FHIRcast release the hub
 * beside them from the same read, and nothing for a session that holds none, does not exist, or
 * has outlived the absolute session lifetime.
 */
class EpicTokensAdapterTests {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);

    private final Map<String, org.springframework.session.Session> store = new ConcurrentHashMap<>();

    /** How many times the adapter found a session in the store. */
    private final AtomicInteger finds = new AtomicInteger();

    private final MapSessionRepository sessions = new MapSessionRepository(store) {
        @Override
        public MapSession findById(String id) {
            finds.incrementAndGet();
            return super.findById(id);
        }
    };

    private final EpicTokens adapter = new EpicTokensAdapter(sessions,
            new AbsoluteSessionLifetimePolicy(ABSOLUTE_LIFETIME), Clock.fixed(NOW, ZoneOffset.UTC));

    private static final EpicTokenSet TOKENS = EpicTokenSet.issued("access-token-value",
            Duration.ofHours(1), NOW, Set.of("openid"), Optional.empty(), "id-token-value");

    private static final FhircastHub HUB =
            FhircastHub.of("http://127.0.0.1:8103/fhircast/STU3", "topic-1").orElseThrow();

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

    /**
     * What the FHIRcast release reads: the tokens and the hub from one find of the session, so a
     * session that ends meanwhile cannot answer the one and not the other.
     */
    @Test
    void forFhircastASessionHoldingTokensAndAHubAnswersBothFromOneFind() {
        String id = open(NOW.minus(Duration.ofMinutes(5)), TOKENS, HUB);
        finds.set(0);

        Optional<HeldForFhircast> held = adapter.forFhircast(id);

        assertThat(List.of(held.map(HeldForFhircast::tokens), held.flatMap(HeldForFhircast::hub),
                finds.get())).containsExactly(Optional.of(TOKENS), Optional.of(HUB), 1);
    }

    @Test
    void forFhircastASessionHoldingTokensAndNoHubAnswersTheTokensAlone() {
        String id = open(NOW.minus(Duration.ofMinutes(5)), TOKENS);

        assertThat(adapter.forFhircast(id))
                .contains(new HeldForFhircast(TOKENS, Optional.empty()));
    }

    /** A hub is never handed out without the tokens it was kept beside. */
    @Test
    void forFhircastASessionHoldingNoTokensAnswersEmptyWhateverHubItHolds() {
        String id = open(NOW.minus(Duration.ofMinutes(5)), null, HUB);

        assertThat(adapter.forFhircast(id)).isEmpty();
    }

    @Test
    void forFhircastASessionPastItsAbsoluteLifetimeAnswersEmpty() {
        String id = open(NOW.minus(ABSOLUTE_LIFETIME).minusSeconds(1), TOKENS, HUB);

        assertThat(adapter.forFhircast(id)).isEmpty();
    }

    @Test
    void forFhircastNoSessionIdAnswersEmpty() {
        assertThat(adapter.forFhircast(null)).isEmpty();
    }

    private String open(Instant createdAt, EpicTokenSet tokens) {
        return open(createdAt, tokens, null);
    }

    /**
     * A session created at {@code createdAt}, holding {@code tokens} and {@code hub} when there
     * are any. Its idle bound is set far beyond any test, because the map repository measures it
     * on the system clock and this test is about the absolute lifetime alone.
     */
    private String open(Instant createdAt, EpicTokenSet tokens, FhircastHub hub) {
        MapSession session = new MapSession();
        session.setCreationTime(createdAt);
        session.setLastAccessedTime(Instant.now());
        session.setMaxInactiveInterval(Duration.ofDays(365_000));
        if (tokens != null) {
            session.setAttribute(SignedInSession.EPIC_TOKENS_ATTRIBUTE, tokens);
        }
        if (hub != null) {
            session.setAttribute(SignedInSession.FHIRCAST_HUB_ATTRIBUTE, hub);
        }
        sessions.save(session);
        return session.getId();
    }
}
