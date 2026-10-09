package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

/**
 * The Epic tokens one Epic Login kept: the access token and its expiry, the scope it was granted,
 * the refresh token when Epic issued one, and the {@code id_token} (ADR 0013, addendum
 * 2026-10-09).
 */
class EpicTokenSetTests {

    private static final Instant RECEIVED = Instant.parse("2026-10-09T09:00:00Z");

    private static final String ACCESS = "access-token-value-1234";

    private static final String REFRESH = "refresh-token-value-5678";

    private static final String ID_TOKEN = "id-token-value-9012";

    private static EpicTokenSet issued(Optional<String> refreshToken) {
        return EpicTokenSet.issued(ACCESS, Duration.ofSeconds(3600), RECEIVED,
                Set.of("launch", "openid", "fhirUser"), refreshToken, ID_TOKEN);
    }

    private static EpicTokenSet withRefreshToken() {
        return issued(Optional.of(REFRESH));
    }

    private static EpicTokenSet withoutRefreshToken() {
        return issued(Optional.empty());
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void theAccessTokenIsKeptAsIssued() {
        assertThat(withRefreshToken().accessToken()).isEqualTo(ACCESS);
    }

    @Test
    void theAccessTokenExpiresItsLifetimeAfterItWasReceived() {
        assertThat(withRefreshToken().accessTokenExpiresAt())
                .isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
    }

    @Test
    void theGrantedScopeIsKept() {
        assertThat(withRefreshToken().scope())
                .containsExactlyInAnyOrder("launch", "openid", "fhirUser");
    }

    @Test
    void theGrantedScopeIsACopyTheIssuerCannotChangeAfterwards() {
        Set<String> granted = new HashSet<>(List.of("openid"));
        EpicTokenSet tokens = EpicTokenSet.issued(ACCESS, Duration.ofSeconds(60), RECEIVED, granted,
                Optional.empty(), ID_TOKEN);

        granted.add("offline_access");

        assertThat(tokens.scope()).containsExactly("openid");
    }

    @Test
    void theRefreshTokenIsKeptWhenEpicIssuedOne() {
        assertThat(withRefreshToken().refreshToken()).contains(REFRESH);
    }

    @Test
    void theRefreshTokenIsEmptyWhenEpicIssuedNone() {
        assertThat(withoutRefreshToken().refreshToken()).isEmpty();
    }

    @Test
    void theIdTokenIsKeptAsIssued() {
        assertThat(withRefreshToken().idToken()).isEqualTo(ID_TOKEN);
    }

    @Test
    void theAccessTokenIsNotExpiredBeforeItsExpiry() {
        assertThat(withRefreshToken()
                .isExpired(at(Instant.parse("2026-10-09T09:59:59Z")))).isFalse();
    }

    @Test
    void theAccessTokenIsExpiredAtItsExpiry() {
        assertThat(withRefreshToken()
                .isExpired(at(Instant.parse("2026-10-09T10:00:00Z")))).isTrue();
    }

    @Test
    void theAccessTokenIsExpiredAfterItsExpiry() {
        assertThat(withRefreshToken()
                .isExpired(at(Instant.parse("2026-10-09T12:00:00Z")))).isTrue();
    }

    @Test
    void anAccessTokenIsRequired() {
        assertThatThrownBy(() -> EpicTokenSet.issued(null, Duration.ofSeconds(60), RECEIVED,
                Set.of(), Optional.empty(), ID_TOKEN))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("accessToken");
    }

    /** "None issued" is {@code Optional.empty()}, never a null in the refresh token's place. */
    @Test
    void aRefreshTokenOrItsAbsenceIsRequired() {
        assertThatThrownBy(() -> EpicTokenSet.issued(ACCESS, Duration.ofSeconds(60), RECEIVED,
                Set.of(), null, ID_TOKEN))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("refreshToken");
    }

    @Test
    void anIdTokenIsRequired() {
        assertThatThrownBy(() -> EpicTokenSet.issued(ACCESS, Duration.ofSeconds(60), RECEIVED,
                Set.of(), Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("idToken");
    }

    /** D22: the value's text names none of its three tokens, so no log or message can carry one. */
    @Test
    void itsTextCarriesNoneOfTheTokens() {
        String text = withRefreshToken().toString();

        assertThat(List.of(ACCESS, REFRESH, ID_TOKEN))
                .allSatisfy(token -> assertThat(text).doesNotContain(token));
    }

    @Test
    void itsTextSaysWhatItIsWithoutTheTokens() {
        assertThat(withRefreshToken().toString()).isEqualTo(
                "EpicTokenSet[accessToken=<redacted>,"
                + " accessTokenExpiresAt=2026-10-09T10:00:00Z, scope=[fhirUser, launch, openid],"
                + " refreshToken=<redacted>, idToken=<redacted>]");
    }

    @Test
    void itsTextSaysWhenNoRefreshTokenWasIssued() {
        assertThat(withoutRefreshToken().toString()).contains("refreshToken=<none>");
    }

    /** The session store keeps it Java-serialized, so it must come back whole. */
    @Test
    void itRoundTripsThroughTheSessionStoresSerializerWhole() {
        EpicTokenSet back = roundTrip(withRefreshToken());

        assertThat(List.of(back.accessToken(), back.accessTokenExpiresAt(), back.scope(),
                back.refreshToken(), back.idToken()))
                .containsExactly(ACCESS, Instant.parse("2026-10-09T10:00:00Z"),
                        Set.of("launch", "openid", "fhirUser"), Optional.of(REFRESH), ID_TOKEN);
    }

    @Test
    void itRoundTripsWithNoRefreshToken() {
        assertThat(roundTrip(withoutRefreshToken()).refreshToken()).isEmpty();
    }

    /** Through the serializer Spring Session's Redis repository writes session attributes with. */
    private static EpicTokenSet roundTrip(EpicTokenSet tokens) {
        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();
        return (EpicTokenSet) serializer.deserialize(serializer.serialize(tokens));
    }
}
