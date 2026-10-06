package com.example.backend.auth.epic;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an EC P-384 private key from PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}), the form
 * {@code openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384} writes.
 *
 * <p>P-384 because the client assertion is signed ES384 (D7). A key on any other curve is
 * refused here, at startup, rather than at the first token call.
 *
 * <p>An environment file holds a value on one line, so the armour may be followed by the base64
 * with no line breaks, or with them escaped as a literal {@code \n}; whitespace and escaped line
 * breaks inside the base64 are ignored.
 *
 * <p><strong>A refusal says only that the value is not such a key.</strong> It never carries the
 * underlying exception: the base64 decoder and the key factory both describe their input in
 * their messages, and the input is a private key (D22).
 */
public final class EcP384PrivateKeyPem {

    private static final Pattern ARMOURED = Pattern.compile(
            "^-----BEGIN PRIVATE KEY-----(.*)-----END PRIVATE KEY-----$", Pattern.DOTALL);

    /** A line break written as the two characters {@code \} and {@code n}. */
    private static final String ESCAPED_LINE_BREAK = "\\n";

    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    private static final ECParameterSpec P384 = p384();

    private EcP384PrivateKeyPem() {
    }

    /**
     * The key {@code pem} holds, or empty when it does not hold an EC P-384 private key in
     * PKCS#8 PEM — for any reason, none of which is reported.
     */
    public static Optional<ECPrivateKey> parse(String pem) {
        if (pem == null) {
            return Optional.empty();
        }
        Matcher armoured = ARMOURED.matcher(pem.replace(ESCAPED_LINE_BREAK, "\n").strip());
        if (!armoured.matches()) {
            return Optional.empty();
        }
        try {
            byte[] der = Base64.getDecoder()
                    .decode(WHITESPACE.matcher(armoured.group(1)).replaceAll(""));
            // The EC key factory yields nothing but EC private keys, or throws.
            ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            return isP384(key.getParams()) ? Optional.of(key) : Optional.empty();
        } catch (IllegalArgumentException | GeneralSecurityException unreadable) {
            // Dropped, not wrapped: both messages may quote the key material (see above).
            return Optional.empty();
        }
    }

    /**
     * Whether the key is on P-384, by its curve (field and coefficients). The curve alone decides
     * it: the JDK's EC provider accepts only named curves — a key with explicit parameters is
     * matched to its named curve or refused — so a key on P-384's curve has P-384's generator,
     * order and cofactor too.
     */
    private static boolean isP384(ECParameterSpec params) {
        return params.getCurve().equals(P384.getCurve());
    }

    private static ECParameterSpec p384() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp384r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException unsupported) {
            throw new IllegalStateException("This JVM does not support the EC P-384 curve",
                    unsupported);
        }
    }
}
