package com.example.backend.auth.epic;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Epic Login configuration, bound from the {@code APP_EPIC_*} environment variables through
 * relaxed binding ({@code APP_EPIC_CLIENT_KEY_ID} is {@code app.epic.client-key-id}).
 *
 * <p><strong>No default credentials.</strong> Every URL, the client id and both keys default to
 * absent, because this repository is public: a default here would be a published value any
 * deployment that forgot the variable would ship. Only the two switches (both off) and the two
 * timeouts (2s connect, 5s read, D25) have defaults, and they are written here — not in
 * {@code application.yaml}, which a deployment may replace wholesale — so they are facts about the
 * code.
 *
 * <p><strong>Nothing is checked while the switch is off.</strong> A deployment that does not
 * serve Epic Login needs none of these settings. With it on, {@link #validate} is the one
 * gatekeeper, and it runs at startup: it gives the configuration back as
 * {@link EpicLoginSettings}, each URL and key parsed once.
 *
 * <p>Validation is deliberately hand-written rather than Bean Validation on these fields: Spring
 * Boot's binding-failure report prints the offending property's value, and two of these values
 * are private keys (D22). {@link #toString()} is overridden for the same reason.
 *
 * @param enabled          the feature switch, {@code APP_EPIC_ENABLED}
 * @param fhirBase         {@code APP_EPIC_FHIR_BASE}, the one allowlisted launch {@code iss}
 * @param oauthIssuer      {@code APP_EPIC_OAUTH_ISSUER}, the OIDC issuer
 * @param clientId         {@code APP_EPIC_CLIENT_ID}
 * @param redirectUri      {@code APP_EPIC_REDIRECT_URI}, the registered callback URL
 * @param clientKey        {@code APP_EPIC_CLIENT_KEY}, the active EC P-384 private key as PEM
 * @param clientKeyId      {@code APP_EPIC_CLIENT_KEY_ID}, the active key's {@code kid}
 * @param clientNextKey    {@code APP_EPIC_CLIENT_NEXT_KEY}, the optional next key as PEM
 * @param clientNextKeyId  {@code APP_EPIC_CLIENT_NEXT_KEY_ID}, the next key's {@code kid}
 * @param connectTimeout   {@code APP_EPIC_CONNECT_TIMEOUT}, default 2s
 * @param readTimeout      {@code APP_EPIC_READ_TIMEOUT}, default 5s
 * @param mfaEvidenceRequired {@code APP_EPIC_MFA_EVIDENCE_REQUIRED}, default off: whether the
 *                         {@code id_token} must carry MFA evidence in {@code amr} (D17). Off
 *                         while the Epic organisation's MFA is an attestation; turned on once
 *                         Epic confirms it sends the claim on an EHR launch (ADR 0013, "Open items")
 */
@ConfigurationProperties("app.epic")
public record EpicLoginProperties(
        Boolean enabled,
        String fhirBase,
        String oauthIssuer,
        String clientId,
        String redirectUri,
        String clientKey,
        String clientKeyId,
        String clientNextKey,
        String clientNextKeyId,
        Duration connectTimeout,
        Duration readTimeout,
        Boolean mfaEvidenceRequired) {

    /** D25's outbound connect timeout, when {@code APP_EPIC_CONNECT_TIMEOUT} is unset. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** D25's outbound read timeout, when {@code APP_EPIC_READ_TIMEOUT} is unset. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Resolves the defaults. An empty variable counts as unset — a deployment template that
     * renders {@code APP_EPIC_READ_TIMEOUT=} binds it to {@code null} — so the defaults hold
     * however the variable came to be absent.
     */
    public EpicLoginProperties {
        enabled = enabled != null && enabled;
        fhirBase = unsetIfBlank(fhirBase);
        oauthIssuer = unsetIfBlank(oauthIssuer);
        clientId = unsetIfBlank(clientId);
        redirectUri = unsetIfBlank(redirectUri);
        clientKey = unsetIfBlank(clientKey);
        clientKeyId = unsetIfBlank(clientKeyId);
        clientNextKey = unsetIfBlank(clientNextKey);
        clientNextKeyId = unsetIfBlank(clientNextKeyId);
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
        mfaEvidenceRequired = mfaEvidenceRequired != null && mfaEvidenceRequired;
    }

    /**
     * Refuses this configuration unless every required setting is present and well formed, and
     * otherwise gives it back parsed: each URL as a {@link URI} and each key, with its
     * {@code kid}, as an {@link EpicSigningKey}. Called only with the switch on.
     *
     * @param devProfile whether the {@code dev} profile is active, the one case in which the
     *                   three URLs may be {@code http} (D21) and {@code fhirUser} may be the
     *                   local launcher's relative {@code Practitioner/{id}}
     * @return the accepted configuration
     * @throws InvalidEpicConfigurationException naming the first variable found wanting
     */
    public EpicLoginSettings validate(boolean devProfile) {
        require(FHIR_BASE, fhirBase);
        require(OAUTH_ISSUER, oauthIssuer);
        require(CLIENT_ID, clientId);
        require(REDIRECT_URI, redirectUri);
        require(CLIENT_KEY, clientKey);
        require(CLIENT_KEY_ID, clientKeyId);
        URI fhirBaseUrl = secureUrl(FHIR_BASE, fhirBase, devProfile);
        URI oauthIssuerUrl = secureUrl(OAUTH_ISSUER, oauthIssuer, devProfile);
        URI redirectUrl = secureUrl(REDIRECT_URI, redirectUri, devProfile);
        EpicSigningKey active = new EpicSigningKey(clientKeyId, p384Key(CLIENT_KEY, clientKey));
        EpicSigningKeys signingKeys = new EpicSigningKeys(active, nextSigningKey());
        requirePositive(CONNECT_TIMEOUT, connectTimeout);
        requirePositive(READ_TIMEOUT, readTimeout);
        return new EpicLoginSettings(fhirBaseUrl, oauthIssuerUrl, clientId, redirectUrl,
                signingKeys, connectTimeout, readTimeout, devProfile, mfaEvidenceRequired);
    }

    /**
     * D14: the next key is optional, but it is published under its own kid, so the two come
     * together, and a kid shared with the active key would make the JWKS ambiguous.
     */
    private Optional<EpicSigningKey> nextSigningKey() {
        if (clientNextKey != null && clientNextKeyId == null) {
            throw new InvalidEpicConfigurationException(
                    CLIENT_NEXT_KEY_ID, "is required when " + CLIENT_NEXT_KEY + " is set");
        }
        if (clientNextKeyId != null && clientNextKey == null) {
            throw new InvalidEpicConfigurationException(
                    CLIENT_NEXT_KEY, "is required when " + CLIENT_NEXT_KEY_ID + " is set");
        }
        if (clientNextKey == null) {
            return Optional.empty();
        }
        ECPrivateKey next = p384Key(CLIENT_NEXT_KEY, clientNextKey);
        if (clientNextKeyId.equals(clientKeyId)) {
            throw new InvalidEpicConfigurationException(
                    CLIENT_NEXT_KEY_ID, "must differ from " + CLIENT_KEY_ID);
        }
        return Optional.of(new EpicSigningKey(clientNextKeyId, next));
    }

    /**
     * The settings with each key reduced to whether it is set. The generated form would print
     * both private keys (D22).
     */
    @Override
    public String toString() {
        return "EpicLoginProperties[enabled=" + enabled
                + ", fhirBase=" + fhirBase
                + ", oauthIssuer=" + oauthIssuer
                + ", clientId=" + clientId
                + ", redirectUri=" + redirectUri
                + ", clientKey=" + redacted(clientKey)
                + ", clientKeyId=" + clientKeyId
                + ", clientNextKey=" + redacted(clientNextKey)
                + ", clientNextKeyId=" + clientNextKeyId
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", mfaEvidenceRequired=" + mfaEvidenceRequired + "]";
    }

    private static String redacted(String key) {
        return key == null ? "null" : "[REDACTED]";
    }

    private static void requirePositive(String variable, Duration timeout) {
        if (!timeout.isPositive()) {
            throw new InvalidEpicConfigurationException(variable, "must be positive");
        }
    }

    private static ECPrivateKey p384Key(String variable, String pem) {
        return EcP384PrivateKeyPem.parse(pem)
                .orElseThrow(() -> new InvalidEpicConfigurationException(
                        variable, "must be an EC P-384 private key in PKCS#8 PEM"));
    }

    /**
     * D21: an absolute URL with a host, whose scheme is {@code https} — or {@code http}, in the
     * dev profile only. A value {@link URI} cannot parse is refused the same way, and the parser's
     * exception, which quotes its input, is dropped rather than chained.
     */
    private static URI secureUrl(String variable, String value, boolean httpAllowed) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException unparseable) {
            throw notAnHttpsUrl(variable);
        }
        String scheme = uri.getScheme();
        boolean secure = "https".equals(scheme) || (httpAllowed && "http".equals(scheme));
        // No host covers both a relative reference and an opaque URI such as "https:example".
        if (!secure || uri.getHost() == null) {
            throw notAnHttpsUrl(variable);
        }
        return uri;
    }

    private static InvalidEpicConfigurationException notAnHttpsUrl(String variable) {
        return new InvalidEpicConfigurationException(variable, "must be an absolute https URL");
    }

    static final String FHIR_BASE = "APP_EPIC_FHIR_BASE";
    static final String OAUTH_ISSUER = "APP_EPIC_OAUTH_ISSUER";
    static final String CLIENT_ID = "APP_EPIC_CLIENT_ID";
    static final String REDIRECT_URI = "APP_EPIC_REDIRECT_URI";
    static final String CLIENT_KEY = "APP_EPIC_CLIENT_KEY";
    static final String CLIENT_KEY_ID = "APP_EPIC_CLIENT_KEY_ID";
    static final String CLIENT_NEXT_KEY = "APP_EPIC_CLIENT_NEXT_KEY";
    static final String CLIENT_NEXT_KEY_ID = "APP_EPIC_CLIENT_NEXT_KEY_ID";
    static final String CONNECT_TIMEOUT = "APP_EPIC_CONNECT_TIMEOUT";
    static final String READ_TIMEOUT = "APP_EPIC_READ_TIMEOUT";

    private static void require(String variable, String value) {
        if (value == null) {
            throw new InvalidEpicConfigurationException(
                    variable, "is required when APP_EPIC_ENABLED is true");
        }
    }

    private static String unsetIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
