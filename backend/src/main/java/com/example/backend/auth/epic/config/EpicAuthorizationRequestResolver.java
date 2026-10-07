package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLaunchContext;
import com.example.backend.auth.epic.EpicRoutes;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The authorization request an Epic Login sends the browser with (flow step 2), resolved on the
 * internal hop {@code GET /api/auth/epic/authorize} while a launch is pending.
 *
 * <p>Spring Security's own resolver supplies {@code response_type=code}, {@code client_id}, the
 * registered {@code redirect_uri}, {@code scope}, a {@code state} and — {@code openid} being in
 * scope — a {@code nonce}, each random value carrying well over 128 bits; PKCE adds a
 * {@code code_verifier} of 43 or more characters and its S256 {@code code_challenge}. To that this
 * adds the two parameters an EHR launch needs: the pending {@code launch}, taken out of the
 * session so it is used once, and {@code aud}, the FHIR base the launch was accepted for. No
 * login hint is sent.
 *
 * <p>The pending request — {@code state}, the nonce and the verifier — is kept by the
 * authorization-request repository in the HTTP session, which lives in Redis, so the callback may
 * land on any node. With no launch pending this resolves nothing, and the hop's own handler
 * refuses the request.
 */
final class EpicAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final RequestMatcher AUTHORIZE =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, EpicRoutes.AUTHORIZE);

    private final DefaultOAuth2AuthorizationRequestResolver spring;

    private final URI fhirBase;

    EpicAuthorizationRequestResolver(ClientRegistrationRepository registrations, URI fhirBase) {
        this.spring =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, EpicRoutes.AUTHORIZE);
        this.spring.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());
        this.fhirBase = fhirBase;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (!AUTHORIZE.matches(request)) {
            return null;
        }
        Optional<String> launch = EpicLaunchContext.take(request);
        if (launch.isEmpty()) {
            return null;
        }
        OAuth2AuthorizationRequest resolved =
                spring.resolve(request, EpicClientRegistrations.REGISTRATION_ID);
        return OAuth2AuthorizationRequest.from(resolved)
                .additionalParameters(parameters -> {
                    parameters.put("launch", launch.get());
                    parameters.put("aud", fhirBase.toString());
                })
                // Copied from the resolved request, it was rendered before the two parameters
                // above existed; cleared, the builder renders it afresh with them.
                .authorizationRequestUri((String) null)
                .build();
    }

    /** Only the internal hop starts an Epic Login; nothing else asks for one by id. */
    @Override
    public OAuth2AuthorizationRequest resolve(
            HttpServletRequest request, String clientRegistrationId) {
        return null;
    }
}
