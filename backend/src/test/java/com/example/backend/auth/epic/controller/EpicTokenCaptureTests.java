package com.example.backend.auth.epic.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.epic.EpicTokenHandOff;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * How the token response Spring Security's login filter received reaches the success handler:
 * the filter saves the authorized client through {@link EpicTokenHandOff#REPOSITORY}, which holds
 * it on the request alone, and the success handler takes it from there through
 * {@link EpicTokenCapture} as the Epic tokens to keep — the access token's expiry made absolute
 * from the instant the handler names.
 */
class EpicTokenCaptureTests {

    private static final OAuth2AuthorizedClientRepository REPOSITORY = EpicTokenHandOff.REPOSITORY;

    /** When Spring Security received the token response, by its own clock. */
    private static final Instant ISSUED = Instant.parse("2026-10-09T08:00:00Z");

    /** The instant the success handler takes the tokens at, from the injected clock. */
    private static final Instant RECEIVED = Instant.parse("2026-10-09T09:00:00Z");

    private static final String ID_TOKEN = "header.id-token-claims.signature";

    private static final ClientRegistration EPIC = ClientRegistration.withRegistrationId("epic")
            .clientId("epic-client-id")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://app.example.org/api/auth/epic/callback")
            .authorizationUri("https://epic.example.org/oauth2/authorize")
            .tokenUri("https://epic.example.org/oauth2/token")
            .build();

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private final Authentication signedInAtEpic = epicAuthentication();

    @Test
    void theSavedAccessTokenIsTaken() {
        save(refreshToken("refresh-token-value"));

        assertThat(take().map(EpicTokenSet::accessToken)).contains("access-token-value");
    }

    /** {@code expires_in} of 3600, made absolute from the instant the handler took it at. */
    @Test
    void theAccessTokenExpiresItsLifetimeAfterTheTokensWereTaken() {
        save(null);

        assertThat(take().map(EpicTokenSet::accessTokenExpiresAt))
                .contains(Instant.parse("2026-10-09T10:00:00Z"));
    }

    @Test
    void theGrantedScopeIsTaken() {
        save(null);

        assertThat(take().map(EpicTokenSet::scope)).contains(Set.of("launch", "openid", "fhirUser"));
    }

    @Test
    void theRefreshTokenIsTakenWhenEpicIssuedOne() {
        save(refreshToken("refresh-token-value"));

        assertThat(take().flatMap(EpicTokenSet::refreshToken)).contains("refresh-token-value");
    }

    @Test
    void theRefreshTokenIsEmptyWhenEpicIssuedNone() {
        save(null);

        assertThat(take().flatMap(EpicTokenSet::refreshToken)).isEmpty();
    }

    @Test
    void theIdTokenIsTheValidatedOneTheLoginWasMadeFrom() {
        save(null);

        assertThat(take().map(EpicTokenSet::idToken)).contains(ID_TOKEN);
    }

    @Test
    void nothingIsTakenFromARequestNothingWasSavedOn() {
        assertThat(take()).isEmpty();
    }

    /** Taken once: the token response does not stay on the request after it is handed off. */
    @Test
    void theTokensAreTakenOnce() {
        save(null);
        take();

        assertThat(take()).isEmpty();
    }

    @Test
    void nothingIsTakenForAnAuthenticationThatIsNotEpicsOidcIdentity() {
        save(null);

        assertThat(EpicTokenCapture.take(request,
                new TestingAuthenticationToken("someone", "n/a"), RECEIVED)).isEmpty();
    }

    @Test
    void aRemovedClientIsNotTaken() {
        save(null);

        REPOSITORY.removeAuthorizedClient("epic", signedInAtEpic, request, response);

        assertThat(take()).isEmpty();
    }

    /** The kept tokens are reached through the {@code EpicTokens} port, never loaded from here. */
    @Test
    void theRepositoryLoadsNothing() {
        save(null);

        assertThat((OAuth2AuthorizedClient) REPOSITORY.loadAuthorizedClient(
                "epic", signedInAtEpic, request)).isNull();
    }

    private void save(OAuth2RefreshToken refreshToken) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "access-token-value", ISSUED, ISSUED.plusSeconds(3600),
                Set.of("launch", "openid", "fhirUser"));
        REPOSITORY.saveAuthorizedClient(
                new OAuth2AuthorizedClient(EPIC, "epic-subject", accessToken, refreshToken),
                signedInAtEpic, request, response);
    }

    private Optional<EpicTokenSet> take() {
        return EpicTokenCapture.take(request, signedInAtEpic, RECEIVED);
    }

    private static OAuth2RefreshToken refreshToken(String value) {
        return new OAuth2RefreshToken(value, ISSUED);
    }

    private static Authentication epicAuthentication() {
        OidcIdToken idToken = new OidcIdToken(ID_TOKEN, ISSUED, ISSUED.plusSeconds(300),
                Map.of("sub", "epic-subject", "fhirUser", "Practitioner/abc"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), idToken), List.of(),
                "epic");
    }
}
