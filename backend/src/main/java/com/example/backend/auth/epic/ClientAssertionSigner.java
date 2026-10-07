package com.example.backend.auth.epic;

import java.net.URI;

/**
 * Signs our {@code private_key_jwt} client assertion for Epic's token endpoint (D7): a JWT
 * whose {@code iss} and {@code sub} are our client id, whose {@code aud} is the endpoint it is
 * presented to, with a fresh {@code jti} and a lifetime of a few minutes, signed ES384 under the
 * active key's {@code kid} — never the next key's (D14).
 *
 * <p>An interface because the key's home is expected to move (D16): today the active key is an
 * environment variable ({@link EnvironmentKeyClientAssertionSigner}); the target is an AWS
 * KMS-held key that signs through the KMS API, so the private key never leaves KMS. Callers see
 * only the finished assertion, so that change stays inside one implementation. The token call
 * (a later step) therefore puts this assertion in the request's {@code client_assertion}
 * parameter itself rather than handing a private JWK to a library signer.
 *
 * <p>The assertion is a bearer credential for the token endpoint until it expires: it is never
 * logged or audited (D22).
 */
public interface ClientAssertionSigner {

    /**
     * A new client assertion for {@code tokenEndpoint}, in JWS compact serialization.
     *
     * @param tokenEndpoint Epic's token endpoint, as discovery reports it: the assertion's
     *                      {@code aud}
     */
    String sign(URI tokenEndpoint);
}
