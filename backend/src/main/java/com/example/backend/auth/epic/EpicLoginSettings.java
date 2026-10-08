package com.example.backend.auth.epic;

import java.net.URI;
import java.time.Duration;

/**
 * The Epic Login configuration once {@link EpicLoginProperties#validate} has accepted it, each
 * value parsed once into the type it is used as. It exists only while the switch is on.
 *
 * @param fhirBase       the one allowlisted launch {@code iss}
 * @param oauthIssuer    the OIDC issuer
 * @param clientId       the Epic client id
 * @param redirectUri    the registered callback URL
 * @param signingKeys    the active and optional next signing key
 * @param connectTimeout the outbound connect timeout (D25)
 * @param readTimeout    the outbound read timeout (D25)
 * @param relativeFhirUserAllowed whether {@code fhirUser} may also be the relative
 *                       {@code Practitioner/{id}} the local SMART launcher issues: the dev
 *                       profile only, as with D21's {@code http} allowance
 * @param mfaEvidenceRequired whether the {@code id_token} must carry MFA evidence in
 *                       {@code amr}, the factor recorded being taken from it (D17); otherwise
 *                       the factor is recorded as {@code idp-attested}
 */
public record EpicLoginSettings(
        URI fhirBase,
        URI oauthIssuer,
        String clientId,
        URI redirectUri,
        EpicSigningKeys signingKeys,
        Duration connectTimeout,
        Duration readTimeout,
        boolean relativeFhirUserAllowed,
        boolean mfaEvidenceRequired) {
}
