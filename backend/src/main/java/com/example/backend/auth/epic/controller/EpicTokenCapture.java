package com.example.backend.auth.epic.controller;

import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.epic.EpicTokenHandOff;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The Epic tokens an accepted Epic Login keeps, read from what Spring Security's login filter
 * handed over ({@link EpicTokenHandOff}) and from the validated OpenID Connect identity: the
 * access token, its expiry made absolute on the application's clock, its scope, the refresh token
 * when Epic issued one, and the raw {@code id_token} (ADR 0013, addendum 2026-10-09).
 *
 * <p>Part of the web adapter that writes them, {@link EpicLoginSuccessHandler}, because building
 * the domain value is an adapter's work: the handed-over client is Spring Security's, and the
 * {@code auth.epic} root holding the hand-off depends on no layer.
 */
final class EpicTokenCapture {

    private EpicTokenCapture() {
    }

    /**
     * The Epic tokens of this callback, the hand-off taken off {@code request}.
     *
     * @param epic       the Login's validated OpenID Connect identity, whose {@code id_token} is
     *                   kept
     * @param receivedAt the instant, from the injected clock, the access token's lifetime
     *                   ({@code expires_in}) is counted from
     * @return the tokens; empty when the filter handed nothing over on this request, or when
     *     {@code epic} is not an OpenID Connect identity
     */
    static Optional<EpicTokenSet> take(
            HttpServletRequest request, Authentication epic, Instant receivedAt) {
        Optional<OAuth2AuthorizedClient> handedOver = EpicTokenHandOff.take(request);
        if (handedOver.isEmpty() || !(epic.getPrincipal() instanceof OidcUser identity)) {
            return Optional.empty();
        }
        OAuth2AuthorizedClient client = handedOver.get();
        OAuth2AccessToken access = client.getAccessToken();
        // Spring Security sets both from expires_in, on its own clock, as it reads the response;
        // only the lifetime between them is carried over, onto ours.
        Duration lifetime = Duration.between(access.getIssuedAt(), access.getExpiresAt());
        Optional<String> refresh = Optional.ofNullable(client.getRefreshToken())
                .map(OAuth2RefreshToken::getTokenValue);
        return Optional.of(EpicTokenSet.issued(access.getTokenValue(), lifetime, receivedAt,
                access.getScopes(), refresh, identity.getIdToken().getTokenValue()));
    }
}
