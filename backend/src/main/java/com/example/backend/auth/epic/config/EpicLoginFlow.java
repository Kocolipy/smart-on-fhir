package com.example.backend.auth.epic.config;

import com.example.backend.auth.domain.PendingAuthorizations;
import com.example.backend.auth.epic.CauseChain;
import com.example.backend.auth.epic.ClientAssertionSigner;
import com.example.backend.auth.epic.EpicJwkSource;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicOutboundCall;
import com.example.backend.auth.epic.EpicOutboundException;
import com.example.backend.auth.epic.EpicRoutes;
import com.example.backend.auth.epic.EpicSignInFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
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
import org.springframework.web.client.RestClientException;

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
 *       handed a private key (ADR 0013). It is never retried, whatever happens to it (D26): the
 *       code is single-use, and the clinician can relaunch.
 *   <li><b>{@code id_token}.</b> RS256 only, against Epic's keys from the shared
 *       {@link EpicJwkSource}, with D26's refetch on an unknown {@code kid};
 *       {@code iss}, {@code aud} (and {@code azp} when there are
 *       several audiences), {@code exp} and {@code iat} with a 30-second skew from the injected
 *       {@link Clock}, and the {@code nonce}.
 *   <li><b>Identity only (D8).</b> No user-info call is made and nothing from the token response
 *       is kept: the authorized client — the access token with it — is not saved anywhere, and
 *       the login filter neither rotates the session nor saves a security context, so Epic's
 *       {@code id_token} never reaches the session store. The success handler establishes the
 *       session from our own Login instead.
 * </ul>
 *
 * <p>Any OAuth error or failed check, and any Epic call that failed — discovery at the authorize
 * hop or the callback, the token call, the JWKS — goes to the one {@link EpicSignInFailure},
 * which lands the browser at {@code /?signin=refused} or, when Epic was unavailable,
 * {@code /?signin=unavailable}, its session ended (D23, D24).
 */
public final class EpicLoginFlow {

    /** The clock skew {@code exp} and {@code iat} are checked with (flow step 4). */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** RFC 7523's client assertion type. */
    static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final ClientRegistrationRepository registrations;

    private final EpicAuthorizationRequestResolver authorizationRequests;

    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenCall;

    private final EpicJwkSource epicKeys;

    private final Clock clock;

    private final AuthenticationSuccessHandler signIn;

    private final EpicSignInFailure signInFailure;

    private final EpicAuthorizationRequests pendingRequests;

    private final boolean mfaEvidenceRequired;

    EpicLoginFlow(
            EpicLoginSettings settings,
            ClientRegistrationRepository registrations,
            RestClient epicRestClient,
            EpicJwkSource epicKeys,
            ClientAssertionSigner signer,
            Clock clock,
            AuthenticationSuccessHandler signIn,
            EpicSignInFailure signInFailure,
            PendingAuthorizations pendingAuthorizations) {
        this.registrations = registrations;
        this.authorizationRequests =
                new EpicAuthorizationRequestResolver(registrations, settings.fhirBase());
        this.tokenCall = tokenCall(epicRestClient, signer);
        this.epicKeys = epicKeys;
        this.clock = clock;
        this.signIn = signIn;
        this.signInFailure = signInFailure;
        this.pendingRequests = new EpicAuthorizationRequests(pendingAuthorizations);
        this.mfaEvidenceRequired = settings.mfaEvidenceRequired();
    }

    /** Adds Epic Login to {@code http}, the application chain. */
    public void applyTo(HttpSecurity http) {
        // Ahead of the login filter: the callback's pending request is taken, and the callback
        // checked, before Spring Security sees it (D18, D27).
        http.addFilterBefore(new EpicCallbackFilter(pendingRequests, signInFailure),
                OAuth2LoginAuthenticationFilter.class);
        http.oauth2Login(oauth2 -> oauth2
                // The SPA's root is the login page; naming it keeps Spring Security from
                // generating one of its own.
                .loginPage("/")
                .loginProcessingUrl(EpicRoutes.CALLBACK)
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(NOTHING_KEPT)
                .authorizationEndpoint(authorize -> authorize
                        .authorizationRequestResolver(authorizationRequests)
                        .authorizationRequestRepository(pendingRequests))
                .tokenEndpoint(token -> token.accessTokenResponseClient(tokenCall))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(EpicLoginFlow::identityOnly))
                .successHandler(signIn)
                .failureHandler(signInFailure)
                .withObjectPostProcessor(new ObjectPostProcessor<Object>() {
                    @Override
                    public <O> O postProcess(O object) {
                        if (object instanceof OAuth2AuthorizationRequestRedirectFilter redirect) {
                            // Discovery runs here on a cold cache; its failure is the same
                            // unavailable or refused landing as the callback's, not a 500.
                            redirect.setAuthenticationFailureHandler(signInFailure);
                        }
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
     * The token call: Spring Security's code redemption through {@code epicRestClient}, named
     * {@code token} for the outbound log and meters, with our client assertion added from the
     * signer. The default parameters already carry {@code grant_type}, {@code code},
     * {@code redirect_uri}, {@code client_id} and the PKCE {@code code_verifier}.
     *
     * <p>Exactly one request, never retried (D26): neither this nor the JDK client resends a
     * {@code POST}, a read timeout included. A failure the operator must see — no answer, a
     * {@code 5xx}, our credential refused, an unreadable answer — leaves carrying its
     * {@link EpicOutboundException}; an ordinary refusal of the code ({@code invalid_grant})
     * leaves as it came.
     */
    private static OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenCall(
            RestClient epicRestClient, ClientAssertionSigner signer) {
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(epicRestClient.mutate()
                .defaultRequest(EpicOutboundCall.TOKEN::on)
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
        return grant -> {
            try {
                return client.getTokenResponse(grant);
            } catch (OAuth2AuthorizationException failure) {
                Optional<EpicOutboundException> failed = failedTokenCall(failure);
                if (failed.isEmpty()) {
                    throw failure;
                }
                throw new OAuth2AuthorizationException(failure.getError(), failed.get());
            }
        };
    }

    /**
     * The section 5 failure in a token call that failed, if it is one: Epic's
     * {@code invalid_client} refusing our assertion, or whatever the client met on the way —
     * no answer, a {@code 5xx}, a {@code 401}, an answer it could not read.
     */
    private static Optional<EpicOutboundException> failedTokenCall(
            OAuth2AuthorizationException failure) {
        if (OAuth2ErrorCodes.INVALID_CLIENT.equals(failure.getError().getErrorCode())) {
            return Optional.of(EpicOutboundException.credentialRefused(400));
        }
        return CauseChain.firstOf(failure, RestClientException.class)
                .map(outbound -> EpicOutboundException.of(EpicOutboundCall.TOKEN, outbound));
    }

    /**
     * The {@code id_token} decoder: RS256 only, Epic's keys from the shared {@link EpicJwkSource}
     * — cached, and refetched on an unknown {@code kid} (D26) — and the OpenID Connect claim
     * checks with a 30-second skew from the injected clock.
     */
    private JwtDecoder idTokenDecoder(ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(epicKeys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        OidcIdTokenValidator claims = new OidcIdTokenValidator(registration);
        claims.setClock(clock);
        claims.setClockSkew(CLOCK_SKEW);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                List.of(timestamps, claims, new EpicIdTokenChecks(mfaEvidenceRequired))));
        return decoder;
    }

    /** The validated {@code id_token} as the identity, with no user-info call to Epic. */
    private static OidcUser identityOnly(OidcUserRequest request) {
        return new DefaultOidcUser(List.of(), request.getIdToken());
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
