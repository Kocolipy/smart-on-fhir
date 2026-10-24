package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.domain.FhircastAccess;
import com.example.backend.auth.domain.FhircastHub;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The FHIRcast release (ADR 0016) over a session store it is handed: what it releases, to whom,
 * and the one audit record each answer leaves.
 */
class FhircastAccessServiceTests {

    private static final UUID CALLER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final String SESSION = "session-1";

    private static final Instant RECEIVED = Instant.parse("2026-10-24T09:00:00Z");

    private static final EpicTokenSet TOKENS = EpicTokenSet.issued("access-1",
            Duration.ofHours(1), RECEIVED, Set.of("launch", "openid"), Optional.of("refresh-1"),
            "id-token-1");

    private static final FhircastHub HUB =
            FhircastHub.of("http://127.0.0.1:8103/fhircast/STU3", "topic-1").orElseThrow();

    private final Sessions sessions = new Sessions();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private FhircastAccessService serviceAt(Instant now) {
        return new FhircastAccessService(sessions, audit, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void anEpicSessionWithAHubIsReleasedItsAccessTokenExpiryScopeAndHub() {
        sessions.keep(TOKENS, HUB);

        Optional<FhircastAccess> released = serviceAt(RECEIVED).release(CALLER, SESSION);

        assertThat(released).contains(new FhircastAccess("access-1",
                Instant.parse("2026-10-24T10:00:00Z"), Set.of("launch", "openid"), HUB));
    }

    @Test
    void aReleaseIsRecordedAsOneSuccessForTheCaller() {
        sessions.keep(TOKENS, HUB);

        serviceAt(RECEIVED).release(CALLER, SESSION);

        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.FHIRCAST_TOKEN_RELEASE, CALLER, CALLER, null));
    }

    @Test
    void aSessionWithNoTokensIsReleasedNothingAndRecordedAsNoEpicTokens() {
        Optional<FhircastAccess> released = serviceAt(RECEIVED).release(CALLER, SESSION);

        assertThat(List.of(released, audit.recorded())).containsExactly(Optional.empty(),
                List.of(new Recorded(AuditOperation.FHIRCAST_TOKEN_RELEASE, CALLER, CALLER,
                        "NO_EPIC_TOKENS")));
    }

    @Test
    void aSessionWithNoHubIsReleasedNothingAndRecordedAsNoFhircastHub() {
        sessions.keep(TOKENS, null);

        Optional<FhircastAccess> released = serviceAt(RECEIVED).release(CALLER, SESSION);

        assertThat(List.of(released, audit.recorded())).containsExactly(Optional.empty(),
                List.of(new Recorded(AuditOperation.FHIRCAST_TOKEN_RELEASE, CALLER, CALLER,
                        "NO_FHIRCAST_HUB")));
    }

    @Test
    void anExpiredAccessTokenIsReleasedNothingAndRecordedAsTokenExpired() {
        sessions.keep(TOKENS, HUB);

        Optional<FhircastAccess> released =
                serviceAt(Instant.parse("2026-10-24T10:00:00Z")).release(CALLER, SESSION);

        assertThat(List.of(released, audit.recorded())).containsExactly(Optional.empty(),
                List.of(new Recorded(AuditOperation.FHIRCAST_TOKEN_RELEASE, CALLER, CALLER,
                        "TOKEN_EXPIRED")));
    }

    @Test
    void anotherSessionsTokensAreNotReleased() {
        sessions.keep(TOKENS, HUB);

        assertThat(serviceAt(RECEIVED).release(CALLER, "session-2")).isEmpty();
    }

    /** What the session store holds, by session id. */
    private static final class Sessions implements EpicTokens {

        private final Map<String, EpicTokenSet> tokens = new HashMap<>();

        private final Map<String, FhircastHub> hubs = new HashMap<>();

        void keep(EpicTokenSet kept, FhircastHub hub) {
            tokens.put(SESSION, kept);
            if (hub != null) {
                hubs.put(SESSION, hub);
            }
        }

        @Override
        public Optional<EpicTokenSet> forSession(String sessionId) {
            return Optional.ofNullable(tokens.get(sessionId));
        }

        @Override
        public Optional<FhircastHub> fhircastHubForSession(String sessionId) {
            return Optional.ofNullable(hubs.get(sessionId));
        }
    }
}
