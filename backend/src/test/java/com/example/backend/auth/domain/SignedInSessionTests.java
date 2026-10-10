package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * The signed-in session's shape, over a map-backed session: the module reads and writes nothing
 * but the attribute map, the creation time and the idle bound, so a map stands in for the
 * servlet container's session and for Spring Session's alike.
 */
class SignedInSessionTests {

    private static final UUID ADA = UUID.fromString("0b7c6a52-5c0e-4a8f-9f61-3d2f9a1c7e10");

    /** Eight hours from a creation at 08:00Z: the lifetime ends at 16:00Z. */
    private static final AbsoluteSessionLifetimePolicy LIFETIME =
            new AbsoluteSessionLifetimePolicy(Duration.ofHours(8));

    private static final EpicTokenSet TOKENS = EpicTokenSet.issued("access-token-value",
            Duration.ofHours(1), Instant.parse("2026-10-10T08:00:00Z"), Set.of("openid"),
            Optional.empty(), "id-token-value");

    // ---- the owner's stable id ------------------------------------------------------------------

    @Test
    void aSessionIndexedByAStableIdIsOwnedByIt() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.OWNER_ATTRIBUTE, "0b7c6a52-5c0e-4a8f-9f61-3d2f9a1c7e10");

        assertThat(SignedInSession.of(session).owner()).contains(ADA);
    }

    @Test
    void aSessionWithNoIndexIdentifiesNobody() {
        assertThat(SignedInSession.of(new MapBackedSession()).owner()).isEmpty();
    }

    /** A value that does not parse as a stable id is not one: nobody, never a failure. */
    @Test
    void aMalformedIndexIdentifiesNobody() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.OWNER_ATTRIBUTE, "not-an-id");

        assertThat(SignedInSession.of(session).owner()).isEmpty();
    }

    /** The index is written as a string; anything else under its name is not an id either. */
    @Test
    void anIndexOfAnotherTypeIdentifiesNobody() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.OWNER_ATTRIBUTE, ADA);

        assertThat(SignedInSession.of(session).owner()).isEmpty();
    }

    /**
     * The owner is kept under Spring Session's principal index, the attribute its indexed
     * repository finds a User's sessions by. Spelled out in the domain, which does not depend on
     * Spring Session, so this pins the two spellings together.
     */
    @Test
    void theOwnerIsKeptUnderSpringSessionsPrincipalIndex() {
        assertThat(SignedInSession.OWNER_ATTRIBUTE)
                .isEqualTo(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    }

    // ---- signing in ----------------------------------------------------------------------------

    @Test
    void aSessionSignedInIsOwnedByItsUser() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());

        signedIn.signIn(ADA, "mapping-hash");

        assertThat(signedIn.owner()).contains(ADA);
    }

    /**
     * Spring Session's indexed repository finds a session by the string its principal index
     * holds, so the owner is written as the stable id's canonical string.
     */
    @Test
    void signingInIndexesTheSessionByTheStableIdsString() {
        MapBackedSession session = new MapBackedSession();

        SignedInSession.of(session).signIn(ADA, "mapping-hash");

        assertThat(session.attributes)
                .containsEntry(SignedInSession.OWNER_ATTRIBUTE, "0b7c6a52-5c0e-4a8f-9f61-3d2f9a1c7e10");
    }

    // ---- the role-mapping hash -----------------------------------------------------------------

    @Test
    void aSessionSignedInUnderAMappingWasIssuedUnderIt() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());

        signedIn.signIn(ADA, "mapping-hash");

        assertThat(signedIn.issuedUnder("mapping-hash")).isTrue();
    }

    @Test
    void aSessionSignedInUnderAMappingWasNotIssuedUnderAnother() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());

        signedIn.signIn(ADA, "mapping-hash");

        assertThat(signedIn.issuedUnder("another-hash")).isFalse();
    }

    /** One that predates the hash being recorded was issued under no mapping this one knows. */
    @Test
    void aSessionCarryingNoHashWasIssuedUnderNoMapping() {
        assertThat(SignedInSession.of(new MapBackedSession()).issuedUnder("mapping-hash")).isFalse();
    }

    // ---- the absolute session lifetime ---------------------------------------------------------

    @Test
    void aSessionPastItsAbsoluteLifetimeHasOutlivedIt() {
        assertThat(SignedInSession.of(new MapBackedSession())
                .hasOutlived(LIFETIME, Instant.parse("2026-10-10T16:00:01Z"))).isTrue();
    }

    /** At the boundary the session is still within its lifetime, as the policy has it. */
    @Test
    void aSessionExactlyAtItsAbsoluteLifetimeHasNotOutlivedIt() {
        assertThat(SignedInSession.of(new MapBackedSession())
                .hasOutlived(LIFETIME, Instant.parse("2026-10-10T16:00:00Z"))).isFalse();
    }

    // ---- Epic tokens ---------------------------------------------------------------------------

    @Test
    void aSessionKeepingEpicTokensHandsThemOut() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());

        signedIn.keepEpicTokens(TOKENS, LIFETIME, Instant.parse("2026-10-10T09:00:00Z"));

        assertThat(signedIn.epicTokens(LIFETIME, Instant.parse("2026-10-10T09:00:00Z")))
                .containsSame(TOKENS);
    }

    /** A password Login's session: signed in, but nothing of Epic's in it. */
    @Test
    void aSessionHoldingNoEpicTokensHandsOutNone() {
        assertThat(SignedInSession.of(new MapBackedSession())
                .epicTokens(LIFETIME, Instant.parse("2026-10-10T09:00:00Z"))).isEmpty();
    }

    /** A session that has ended by its age hands out nothing, whatever the store still holds. */
    @Test
    void aSessionPastItsAbsoluteLifetimeHandsOutNoEpicTokens() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.EPIC_TOKENS_ATTRIBUTE, TOKENS);

        assertThat(SignedInSession.of(session)
                .epicTokens(LIFETIME, Instant.parse("2026-10-10T16:00:01Z"))).isEmpty();
    }

    @Test
    void aSessionExactlyAtItsAbsoluteLifetimeStillHandsOutItsEpicTokens() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.EPIC_TOKENS_ATTRIBUTE, TOKENS);

        assertThat(SignedInSession.of(session)
                .epicTokens(LIFETIME, Instant.parse("2026-10-10T16:00:00Z"))).containsSame(TOKENS);
    }

    /** Something else under the tokens' name is not a token set, and is not handed out. */
    @Test
    void anEpicTokensAttributeOfAnotherTypeHandsOutNone() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.EPIC_TOKENS_ATTRIBUTE, "not a token set");

        assertThat(SignedInSession.of(session)
                .epicTokens(LIFETIME, Instant.parse("2026-10-10T09:00:00Z"))).isEmpty();
    }

    // ---- the idle bound, cut to the lifetime's end ----------------------------------------------

    /**
     * Kept with ten minutes of the lifetime left and a thirty-minute idle bound: the store may
     * keep the session ten minutes, so the tokens are never stored past the lifetime's end.
     */
    @Test
    void keepingEpicTokensNearTheLifetimesEndCutsTheIdleBoundToWhatRemains() {
        MapBackedSession session = new MapBackedSession();

        SignedInSession.of(session)
                .keepEpicTokens(TOKENS, LIFETIME, Instant.parse("2026-10-10T15:50:00Z"));

        assertThat(session.idleBound).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void keepingEpicTokensFarFromTheLifetimesEndLeavesTheIdleBound() {
        MapBackedSession session = new MapBackedSession();

        SignedInSession.of(session)
                .keepEpicTokens(TOKENS, LIFETIME, Instant.parse("2026-10-10T09:00:00Z"));

        assertThat(session.idleBound).isEqualTo(Duration.ofMinutes(30));
    }

    /** What remains, 14m 29.5s, is counted in whole seconds rounded down. */
    @Test
    void aSessionHoldingEpicTokensNearTheLifetimesEndIsBoundedByWhatRemains() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.EPIC_TOKENS_ATTRIBUTE, TOKENS);

        SignedInSession.of(session)
                .boundByLifetime(LIFETIME, Instant.parse("2026-10-10T15:45:30.500Z"));

        assertThat(session.idleBound).isEqualTo(Duration.ofSeconds(869));
    }

    @Test
    void aSessionHoldingEpicTokensFarFromTheLifetimesEndKeepsItsIdleBound() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.EPIC_TOKENS_ATTRIBUTE, TOKENS);

        SignedInSession.of(session).boundByLifetime(LIFETIME, Instant.parse("2026-10-10T09:00:00Z"));

        assertThat(session.idleBound).isEqualTo(Duration.ofMinutes(30));
    }

    /** Only a session holding Epic tokens is bounded; any other keeps its idle bound. */
    @Test
    void aSessionHoldingNoEpicTokensKeepsItsIdleBoundNearTheLifetimesEnd() {
        MapBackedSession session = new MapBackedSession();

        SignedInSession.of(session).boundByLifetime(LIFETIME, Instant.parse("2026-10-10T15:50:00Z"));

        assertThat(session.idleBound).isEqualTo(Duration.ofMinutes(30));
    }

    /**
     * Whatever is stored under the tokens' name is bounded, not only a well-formed token set: the
     * bound is about what the store keeps, and it keeps that value as long as the session.
     */
    @Test
    void anythingUnderTheEpicTokensNameIsBoundedByWhatRemains() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.EPIC_TOKENS_ATTRIBUTE, "not a token set");

        SignedInSession.of(session).boundByLifetime(LIFETIME, Instant.parse("2026-10-10T15:50:00Z"));

        assertThat(session.idleBound).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * A session as a map of attributes, a creation time and an idle bound: the three things the
     * module reads and writes.
     */
    static final class MapBackedSession implements SignedInSession.Attributes {

        final Map<String, Object> attributes = new HashMap<>();

        final Instant createdAt;

        Duration idleBound;

        MapBackedSession() {
            this(Instant.parse("2026-10-10T08:00:00Z"), Duration.ofMinutes(30));
        }

        MapBackedSession(Instant createdAt, Duration idleBound) {
            this.createdAt = createdAt;
            this.idleBound = idleBound;
        }

        @Override
        public Object attribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public Instant createdAt() {
            return createdAt;
        }

        @Override
        public Duration idleBound() {
            return idleBound;
        }

        @Override
        public void setIdleBound(Duration idleBound) {
            this.idleBound = idleBound;
        }
    }
}
