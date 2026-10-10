package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicProviderMetadata;
import com.example.backend.auth.epic.EpicProviderMetadata.Endpoints;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * The one OAuth client this deployment is (D4): us, registered with the one Epic organisation,
 * under the registration id {@value #REGISTRATION_ID}.
 *
 * <p>Built on each lookup from the settings and from discovery, so startup never contacts Epic
 * (D26) — and a registration Spring Security caches nowhere, which keeps a deployment's Epic
 * endpoints exactly what discovery last said, at most 24 hours ago. A lookup that finds Epic
 * unavailable fails with the {@code EpicOutboundException} discovery threw, which reaches the
 * Epic failure handler at the authorize hop and at the callback alike.
 *
 * <ul>
 *   <li>client authentication {@code private_key_jwt} (D7): the token call adds our client
 *       assertion itself, from the {@code ClientAssertionSigner}, rather than handing a library
 *       signer a private key (ADR 0013);
 *   <li>{@code redirect_uri} exactly {@code APP_EPIC_REDIRECT_URI}, with no template to expand;
 *   <li>{@code scope=launch openid fhirUser}, in that order: identity only (D8), so the access
 *       token kept for the session (ADR 0013, D29) reads no FHIR resource and
 *       Epic issues no refresh token;
 *   <li>the issuer, so the {@code id_token}'s {@code iss} must equal it.
 * </ul>
 */
final class EpicClientRegistrations implements ClientRegistrationRepository {

    /** The registration id; never shown to anyone, and not part of any route. */
    static final String REGISTRATION_ID = "epic";

    private final EpicLoginSettings settings;

    private final EpicProviderMetadata metadata;

    EpicClientRegistrations(EpicLoginSettings settings, EpicProviderMetadata metadata) {
        this.settings = settings;
        this.metadata = metadata;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        Endpoints epic = metadata.discover();
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(settings.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(settings.redirectUri().toString())
                .scope("launch", "openid", "fhirUser")
                .authorizationUri(epic.authorizationEndpoint().toString())
                .tokenUri(epic.tokenEndpoint().toString())
                .jwkSetUri(epic.jwksUri().toString())
                .issuerUri(epic.issuer().toString())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Epic")
                .build();
    }
}
