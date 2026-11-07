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

    /** Grace: the User a later Login over Ada's session signs it in to. */
    private static final UUID GRACE = UUID.fromString("7d1e2f30-4a5b-4c6d-8e9f-a0b1c2d3e4f5");

    /** A Login at noon over a session created at 08:00Z. */
    private static final Instant NOON = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void aSessionSignedInIsOwnedByItsUser() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());

        signedIn.signIn(ADA, NOON);

        assertThat(signedIn.owner()).contains(ADA);
    }

    /**
     * Spring Session's indexed repository finds a session by the string its principal index
     * holds, so the owner is written as the stable id's canonical string.
     */
    @Test
    void signingInIndexesTheSessionByTheStableIdsString() {
        MapBackedSession session = new MapBackedSession();

        SignedInSession.of(session).signIn(ADA, NOON);

        assertThat(session.attributes)
                .containsEntry(SignedInSession.OWNER_ATTRIBUTE, "0b7c6a52-5c0e-4a8f-9f61-3d2f9a1c7e10");
    }

    /** Signing in records whose session it is and when the sign-in began, and nothing else. */
    @Test
    void signingInRecordsTheOwnerAndWhenItBeganAlone() {
        MapBackedSession session = new MapBackedSession();

        SignedInSession.of(session).signIn(ADA, NOON);

        assertThat(session.attributes).containsOnlyKeys(
                "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME",
                "app.signed-in-at");
    }

    /**
     * A Login over a session an EHR launch signed in: the launch's Epic tokens are not the new
     * User's, and are gone.
     */
    @Test
    void signingInOverAnEarlierSignInLeavesNoEpicTokens() {
        SignedInSession session = SignedInSession.of(new MapBackedSession());
        session.signIn(ADA, Instant.parse("2026-10-10T09:00:00Z"));
        session.keepEpicTokens(TOKENS, LIFETIME, Instant.parse("2026-10-10T09:00:00Z"));

        session.signIn(GRACE, NOON);

        assertThat(session.epicTokens(LIFETIME, NOON)).isEmpty();
    }

    /** Nor is the launch's FHIRcast hub: a password Login's session holds none. */
    @Test
    void signingInOverAnEarlierSignInLeavesNoHub() {
        SignedInSession session = SignedInSession.of(new MapBackedSession());
        session.signIn(ADA, Instant.parse("2026-10-10T09:00:00Z"));
        session.keepFhircastHub(HUB);

        session.signIn(GRACE, NOON);

        assertThat(session.fhircastHub(LIFETIME, NOON)).isEmpty();
    }

    /**
     * Everything an earlier sign-in kept goes, whatever it was: what is left is the new sign-in
     * alone, the new owner and when it began.
     */
    @Test
    void signingInOverAnEarlierSignInLeavesOnlyTheNewOne() {
        MapBackedSession stored = new MapBackedSession();
        SignedInSession session = SignedInSession.of(stored);
        session.signIn(ADA, Instant.parse("2026-10-10T09:00:00Z"));
        session.keepFhircastHub(HUB);
        session.keepEpicTokens(TOKENS, LIFETIME, Instant.parse("2026-10-10T09:00:00Z"));

        session.signIn(GRACE, NOON);

        assertThat(stored.attributes).containsOnly(
                Map.entry(SignedInSession.OWNER_ATTRIBUTE, "7d1e2f30-4a5b-4c6d-8e9f-a0b1c2d3e4f5"),
                Map.entry("app.signed-in-at", NOON));
    }

    /**
     * What is not the sign-in's own is left where it is: the security context the Login saved
     * just before signing the session in, say, which the session's own framework owns.
     */
    @Test
    void signingInLeavesWhatIsNotTheSignInsOwn() {
        MapBackedSession stored = new MapBackedSession();
        stored.attributes.put("SPRING_SECURITY_CONTEXT", "the saved context");

        SignedInSession.of(stored).signIn(ADA, NOON);

        assertThat(stored.attributes).containsEntry("SPRING_SECURITY_CONTEXT", "the saved context");
    }

    // ---- the absolute session lifetime ---------------------------------------------------------

    /**
     * Counted from the Login, not from the session's creation: signed in at noon, a session
     * created at 08:00Z is still within its 8 hours at 16:00:01Z.
     */
    @Test
    void aSessionSignedInLaterHasNotOutlivedItsLifetimeEightHoursAfterItsCreation() {
        SignedInSession session = SignedInSession.of(new MapBackedSession());
        session.signIn(ADA, NOON);

        assertThat(session.hasOutlived(LIFETIME, Instant.parse("2026-10-10T16:00:01Z"))).isFalse();
    }

    /** And has outlived it once 8 hours have passed since the Login. */
    @Test
    void aSessionSignedInLaterOutlivesItsLifetimeEightHoursAfterTheLogin() {
        SignedInSession session = SignedInSession.of(new MapBackedSession());
        session.signIn(ADA, NOON);

        assertThat(session.hasOutlived(LIFETIME, Instant.parse("2026-10-10T20:00:01Z"))).isTrue();
    }

    /**
     * The Login that counts is the latest: a session signed in again at noon is measured from
     * noon, not from the first sign-in at 09:00Z.
     */
    @Test
    void aSessionSignedInAgainCountsFromTheLatestLogin() {
        SignedInSession session = SignedInSession.of(new MapBackedSession());
        session.signIn(ADA, Instant.parse("2026-10-10T09:00:00Z"));
        session.signIn(GRACE, NOON);

        assertThat(session.hasOutlived(LIFETIME, Instant.parse("2026-10-10T17:00:01Z"))).isFalse();
    }

    /**
     * Something else under the sign-in instant's name is no instant: the session counts from its
     * creation, as one nobody signed in to does.
     */
    @Test
    void aSignInInstantOfAnotherTypeCountsFromTheSessionsCreation() {
        MapBackedSession stored = new MapBackedSession();
        stored.attributes.put("app.signed-in-at", "not an instant");

        assertThat(SignedInSession.of(stored)
                .hasOutlived(LIFETIME, Instant.parse("2026-10-10T16:00:01Z"))).isTrue();
    }

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

    // ---- the FHIRcast hub (ADR 0016) ------------------------------------------------------------

    private static final FhircastHub HUB =
            FhircastHub.of("http://127.0.0.1:8103/fhircast/STU3", "topic-1").orElseThrow();

    @Test
    void aSessionKeepingAHubHandsItOut() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());
        signedIn.keepFhircastHub(HUB);

        assertThat(signedIn.fhircastHub(LIFETIME, Instant.parse("2026-10-10T09:00:00Z")))
                .containsSame(HUB);
    }

    @Test
    void aSessionKeepingNoHubHandsOutNone() {
        assertThat(SignedInSession.of(new MapBackedSession())
                .fhircastHub(LIFETIME, Instant.parse("2026-10-10T09:00:00Z"))).isEmpty();
    }

    @Test
    void aSessionPastItsAbsoluteLifetimeHandsOutNoHub() {
        SignedInSession signedIn = SignedInSession.of(new MapBackedSession());
        signedIn.keepFhircastHub(HUB);

        assertThat(signedIn.fhircastHub(LIFETIME, Instant.parse("2026-10-10T16:00:01Z")))
                .isEmpty();
    }

    @Test
    void aHubAttributeOfAnotherTypeHandsOutNone() {
        MapBackedSession session = new MapBackedSession();
        session.attributes.put(SignedInSession.FHIRCAST_HUB_ATTRIBUTE, "not a hub");

        assertThat(SignedInSession.of(session)
                .fhircastHub(LIFETIME, Instant.parse("2026-10-10T09:00:00Z"))).isEmpty();
    }

    @Test
    void theHubIsKeptUnderItsOwnAttribute() {
        MapBackedSession session = new MapBackedSession();
        SignedInSession.of(session).keepFhircastHub(HUB);

        assertThat(session.attributes).containsOnlyKeys("app.epic.fhircast-hub");
    }

    // ---- the idle bound, cut to the lifetime's end ----------------------------------------------

    /**
     * The lifetime's end is the Login's: signed in at noon and kept at 19:50Z, ten minutes remain,
     * though eight hours from the session's creation would be long past.
     */
    @Test
    void keepingEpicTokensCutsTheIdleBoundToWhatRemainsSinceTheLogin() {
        MapBackedSession stored = new MapBackedSession();
        SignedInSession session = SignedInSession.of(stored);
        session.signIn(ADA, NOON);

        session.keepEpicTokens(TOKENS, LIFETIME, Instant.parse("2026-10-10T19:50:00Z"));

        assertThat(stored.idleBound).isEqualTo(Duration.ofMinutes(10));
    }

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
        public void removeAttribute(String name) {
            attributes.remove(name);
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
