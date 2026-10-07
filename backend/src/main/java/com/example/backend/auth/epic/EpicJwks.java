package com.example.backend.auth.epic;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

/**
 * Our public JWKS, the document Epic verifies our client assertions against (D14): the active
 * key, then the next key when one is configured, each as the EC P-384 <strong>public</strong> key
 * its private key belongs to, under its {@code kid}, for ES384 signatures (D7).
 *
 * <p>The configuration holds only the private keys, so each public key is derived here — once,
 * when the document is built at startup — as the curve's generator multiplied by the private
 * scalar. The document is built from public keys alone, so no private parameter ({@code d}) can
 * be in it.
 *
 * <p>No expiry or period logic (D14): a key is published for exactly as long as configuration
 * names it, and rotation is the operator's ({@code /infra/README.md}, "Signing-key promotion").
 */
public final class EpicJwks {

    private static final ECNamedCurveParameterSpec P384 =
            ECNamedCurveTable.getParameterSpec("secp384r1");

    private final Map<String, Object> document;

    private EpicJwks(List<JWK> keys) {
        this.document = Map.copyOf(new JWKSet(keys).toJSONObject(true));
    }

    /** The active key, then the next key if there is one. */
    public static EpicJwks of(EpicSigningKeys keys) {
        List<JWK> published = new ArrayList<>();
        published.add(publicJwk(keys.active()));
        keys.next().map(EpicJwks::publicJwk).ifPresent(published::add);
        return new EpicJwks(published);
    }

    /** The JWKS of a deployment with no signing keys, which is to say with Epic Login off. */
    public static EpicJwks none() {
        return new EpicJwks(List.of());
    }

    /** The JWKS as its JSON object: {@code {"keys": [...]}}. */
    public Map<String, Object> document() {
        return document;
    }

    /** The public key {@code key} belongs to, Q = d·G on P-384, as a JWK with no private part. */
    private static JWK publicJwk(EpicSigningKey key) {
        ECPoint q = P384.getG().multiply(key.privateKey().getS()).normalize();
        int fieldSize = P384.getCurve().getFieldSize();
        return new ECKey.Builder(Curve.P_384,
                        ECKey.encodeCoordinate(fieldSize, q.getAffineXCoord().toBigInteger()),
                        ECKey.encodeCoordinate(fieldSize, q.getAffineYCoord().toBigInteger()))
                .keyID(key.keyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES384)
                .build();
    }
}
