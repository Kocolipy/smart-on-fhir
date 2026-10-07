package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.ClientAssertionSigner;
import com.example.backend.auth.epic.EpicJwkSource;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicRoutes;
import com.example.backend.auth.epic.EpicSignInRedirect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Epic Login's OAuth 2.0 / OpenID Connect half (flow steps 2–4), as Spring Security's
 * {@code oauth2Login} on the existing application chain, applied by {@code SecurityConfig} while
 * Epic Login is on.
 *
 * <ul>
 *   <li><b>Authorize.</b> {@code GET /api/auth/epic/authorize}, the internal hop the launch
 *       redirects to, is answered by the authorization-request filter with the redirect to Epic,
 *       resolved by {@link EpicAuthorizationRequestResolver}.
 *   <li><b>Callback.</b> {@code GET /api/auth/epic/callback} is the login filter's processing
 *       URL. The pending request is removed from the session on that first read, so a replayed
 *       callback finds none and makes no token call.
 *   <li><b>Token call.</b> The code is redeemed once, through {@code epicRestClient}, with the
 *       same {@code redirect_uri}, the PKCE verifier, and our client assertion — which this adds
 *       to the request itself from the {@link ClientAssertionSigner}, so no library signer is ever
 *       handed a private key (ADR 0013).
 *   <li><b>{@code id_token}.</b> RS256 only, against Epic's discovered {@code jwks_uri} read
 *       through {@code epicRestClient}; {@code iss}, {@code aud} (and {@code azp} when there are
 *       several audiences), {@code exp} and {@code iat} with a 30-second skew from the injected
 *       {@link Clock}, and the {@code nonce}.
 *   <li><b>Identity only (D8).</b> No user-info call is made and nothing from the token response
 *       is kept: the authorized client — the access token with it — is not saved anywhere, and
 *       the login filter neither rotates the session nor saves a security context, so Epic's
 *       {@code id_token} never reaches the session store. The success handler establishes the
 *       session from our own Login instead.
 * </ul>
 *
 * <p>Any OAuth error or failed check lands the browser at {@code /?signin=refused}, its session
 * ended (D23, D24).
 */
public final class EpicLoginFlow {

    /** The clock skew {@code exp} and {@code iat} are checked with (flow step 4). */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** RFC 7523's client assertion type. */
    static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final ClientRegistrationRepository registrations;

    private final EpicAuthorizationRequestResolver authorizationRequests;

    private final RestClientAuthorizationCodeTokenResponseClient tokenCall;

    private final RestClient epicRestClient;

    private final Clock clock;

    private final AuthenticationSuccessHandler signIn;

    EpicLoginFlow(
            EpicLoginSettings settings,
            ClientRegistrationRepository registrations,
            RestClient epicRestClient,
            ClientAssertionSigner signer,
            Clock clock,
            AuthenticationSuccessHandler signIn) {
        this.registrations = registrations;
        this.authorizationRequests =
                new EpicAuthorizationRequestResolver(registrations, settings.fhirBase());
        this.tokenCall = tokenCall(epicRestClient, signer);
        this.epicRestClient = epicRestClient;
        this.clock = clock;
        this.signIn = signIn;
    }

    /** Adds Epic Login to {@code http}, the application chain. */
    public void applyTo(HttpSecurity http) {
        http.oauth2Login(oauth2 -> oauth2
                // The SPA's root is the login page; naming it keeps Spring Security from
                // generating one of its own.
                .loginPage("/")
                .loginProcessingUrl(EpicRoutes.CALLBACK)
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(NOTHING_KEPT)
                .authorizationEndpoint(authorize -> authorize
                        .authorizationRequestResolver(authorizationRequests))
                .tokenEndpoint(token -> token.accessTokenResponseClient(tokenCall))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(EpicLoginFlow::identityOnly))
                .successHandler(signIn)
                .failureHandler(EpicLoginFlow::refuse)
                .withObjectPostProcessor(new ObjectPostProcessor<Object>() {
                    @Override
                    public <O> O postProcess(O object) {
                        if (object instanceof OAuth2LoginAuthenticationFilter filter) {
                            // The session is the success handler's to rotate and sign in.
                            filter.setSessionAuthenticationStrategy(
                                    new NullAuthenticatedSessionStrategy());
                            filter.setSecurityContextRepository(
                                    new NullSecurityContextRepository());
                        }
                        if (object instanceof OidcAuthorizationCodeAuthenticationProvider oidc) {
                            oidc.setJwtDecoderFactory(EpicLoginFlow.this::idTokenDecoder);
                        }
                        return object;
                    }
                }));
    }

    /**
     * The token call: Spring Security's code redemption through {@code epicRestClient}, with our
     * client assertion added from the signer. The default parameters already carry
     * {@code grant_type}, {@code code}, {@code redirect_uri}, {@code client_id} and the PKCE
     * {@code code_verifier}.
     */
    private static RestClientAuthorizationCodeTokenResponseClient tokenCall(
            RestClient epicRestClient, ClientAssertionSigner signer) {
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(epicRestClient.mutate()
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        client.addParametersConverter((OAuth2AuthorizationCodeGrantRequest grant) -> {
            URI tokenEndpoint =
                    URI.create(grant.getClientRegistration().getProviderDetails().getTokenUri());
            MultiValueMap<String, String> assertion = new LinkedMultiValueMap<>();
            assertion.add("client_assertion_type", JWT_BEARER);
            assertion.add("client_assertion", signer.sign(tokenEndpoint));
            return assertion;
        });
        return client;
    }

    /**
     * The {@code id_token} decoder: RS256 only, Epic's keys from its discovered {@code jwks_uri}
     * through {@code epicRestClient}, and the OpenID Connect claim checks with a 30-second skew
     * from the injected clock. Built per Login, so a rediscovered {@code jwks_uri} takes effect at
     * once.
     */
    private JwtDecoder idTokenDecoder(ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(new EpicJwkSource(epicRestClient,
                        URI.create(registration.getProviderDetails().getJwkSetUri())))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        OidcIdTokenValidator claims = new OidcIdTokenValidator(registration);
        claims.setClock(clock);
        claims.setClockSkew(CLOCK_SKEW);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(timestamps, claims)));
        return decoder;
    }

    /** The validated {@code id_token} as the identity, with no user-info call to Epic. */
    private static OidcUser identityOnly(OidcUserRequest request) {
        return new DefaultOidcUser(List.of(), request.getIdToken());
    }

    /** Any OAuth error or failed check: refused, with no detail, its session ended. */
    private static void refuse(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        EpicSignInRedirect.refused(request, response);
    }

    /** D8: the authorized client, and the access token in it, is kept nowhere. */
    private static final OAuth2AuthorizedClientRepository NOTHING_KEPT =
            new OAuth2AuthorizedClientRepository() {
                @Override
                public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                        String clientRegistrationId, Authentication principal,
                        HttpServletRequest request) {
                    return null;
                }

                @Override
                public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                        Authentication principal, HttpServletRequest request,
                        HttpServletResponse response) {
                    // Deliberately nothing: Epic's access token is used for nothing (D8).
                }

                @Override
                public void removeAuthorizedClient(String clientRegistrationId,
                        Authentication principal, HttpServletRequest request,
                        HttpServletResponse response) {
                    // Nothing was kept, so there is nothing to remove.
                }
            };
}
