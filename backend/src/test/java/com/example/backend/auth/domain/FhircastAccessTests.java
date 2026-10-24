package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a FHIRcast release hands the SPA (ADR 0016): the access token of the kept set, its expiry,
 * its scope and the hub — nothing else, and never the token in its text.
 */
class FhircastAccessTests {

    private static final FhircastHub HUB =
            FhircastHub.of("http://127.0.0.1:8103/fhircast/STU3", "topic-1").orElseThrow();

    private static final EpicTokenSet TOKENS = EpicTokenSet.issued("access-1",
            Duration.ofHours(1), Instant.parse("2026-10-24T09:00:00Z"), Set.of("openid", "launch"),
            Optional.of("refresh-1"), "id-token-1");

    @Test
    void itCarriesTheAccessTokenItsExpiryItsScopeAndTheHub() {
        FhircastAccess access = FhircastAccess.of(TOKENS, HUB);

        assertThat(List.of(access.accessToken(), access.expiresAt(), access.scope(), access.hub()))
                .containsExactly("access-1", Instant.parse("2026-10-24T10:00:00Z"),
                        Set.of("launch", "openid"), HUB);
    }

    @Test
    void itHoldsACopyOfTheScopeItWasGiven() {
        Set<String> scope = new HashSet<>(Set.of("openid"));
        FhircastAccess access = new FhircastAccess("access-1", Instant.EPOCH, scope, HUB);

        scope.add("launch");

        assertThat(access.scope()).containsExactly("openid");
    }

    @Test
    void itNeedsAnAccessToken() {
        assertThatThrownBy(() -> new FhircastAccess(null, Instant.EPOCH, Set.of(), HUB))
                .isInstanceOf(NullPointerException.class).hasMessage("accessToken");
    }

    @Test
    void itNeedsAHub() {
        assertThatThrownBy(() -> new FhircastAccess("access-1", Instant.EPOCH, Set.of(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("hub");
    }

    /** D22: the token is named by its presence alone. */
    @Test
    void itNamesTheAccessTokenByItsPresenceAlone() {
        assertThat(new FhircastAccess("access-1", Instant.parse("2026-10-24T10:00:00Z"),
                Set.of("openid"), HUB).toString())
                .isEqualTo("FhircastAccess[accessToken=<redacted>, expiresAt=2026-10-24T10:00:00Z,"
                        + " scope=[openid], hub=FhircastHub[url=http://127.0.0.1:8103/fhircast/STU3,"
                        + " topic=<redacted>]]");
    }
}
