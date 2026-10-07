package com.example.backend.auth.epic;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * The {@link ClientAssertionSigner} for now (D16): it signs with the active key read from
 * {@code APP_EPIC_CLIENT_KEY} at startup. The next key is never used to sign (D14) — this signer
 * takes the active key from {@link EpicSigningKeys} and nothing else from it.
 *
 * <p>Each assertion lives {@link #LIFETIME}: Epic refuses one whose {@code exp} is more than five
 * minutes ahead, and four leaves a minute for our clock running ahead of Epic's. Time comes from
 * the injected {@link Clock}.
 */
public final class EnvironmentKeyClientAssertionSigner implements ClientAssertionSigner {

    /** How long an assertion is valid for, from the moment it is signed. */
    static final Duration LIFETIME = Duration.ofMinutes(4);

    private final String clientId;

    private final EpicSigningKey activeKey;

    private final Clock clock;

    /**
     * @param clientId our Epic client id, the assertion's {@code iss} and {@code sub}
     * @param keys     the configured signing keys, of which only the active one signs
     * @param clock    the source of {@code iat} and {@code exp}
     */
    public EnvironmentKeyClientAssertionSigner(String clientId, EpicSigningKeys keys, Clock clock) {
        this.clientId = clientId;
        this.activeKey = keys.active();
        this.clock = clock;
    }

    @Override
    public String sign(URI tokenEndpoint) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(tokenEndpoint.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES384)
                .type(JOSEObjectType.JWT)
                .keyID(activeKey.keyId())
                .build();
        SignedJWT assertion = new SignedJWT(header, claims);
        try {
            assertion.sign(new ECDSASigner(activeKey.privateKey()));
        } catch (JOSEException failed) {
            // Dropped, not chained: nothing about a signing failure is worth a key-adjacent trace.
            throw new IllegalStateException("Could not sign the Epic client assertion");
        }
        return assertion.serialize();
    }
}
