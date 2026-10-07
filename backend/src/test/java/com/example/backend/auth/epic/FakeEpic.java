package com.example.backend.auth.epic;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Epic's OAuth server, in the test JVM: a JDK {@link HttpServer} serving OIDC discovery, a JWKS,
 * {@code /authorize} and {@code /token} under {@code http://localhost:{port}/oauth2}, the issuer.
 *
 * <p>It holds us to what Epic would. {@code /authorize} remembers the request it was sent and
 * answers with a single-use code. {@code /token} redeems that code only for the same
 * {@code redirect_uri}, the {@code code_verifier} whose S256 hash was the {@code code_challenge},
 * and a client assertion that verifies against OUR published JWKS — signed ES384 by its active
 * {@code kid}, {@code iss} and {@code sub} our client id, {@code aud} this token endpoint, a
 * {@code jti}, and an {@code exp} at most five minutes ahead. It then mints an RS256
 * {@code id_token} for the {@code fhirUser} the test asked for, carrying the nonce it was sent.
 *
 * <p>Its token response carries an access token and patient and encounter context, as Epic's
 * does, under values unique to this instance, so a test can show none of them is kept (D8).
 */
public final class FakeEpic implements AutoCloseable {

    /** The token endpoint's assertion type, RFC 7523. */
    private static final String JWT_BEARER =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;

    private final String issuer;

    private final String clientId;

    private final String activeKeyId;

    private final Supplier<JWKSet> ourJwks;

    private final RSAKey signingKey;

    private final Map<String, Map<String, String>> issuedCodes = new ConcurrentHashMap<>();

    private final Set<String> redeemedCodes = Collections.synchronizedSet(new HashSet<>());

    private final List<String> tokenRefusals = Collections.synchronizedList(new ArrayList<>());

    private final List<Map<String, String>> authorizeRequests =
            Collections.synchronizedList(new ArrayList<>());

    private volatile String fhirUser;

    /** The access token this instance hands out, which nothing on our side may keep. */
    public final String accessToken = "epic-access-" + UUID.randomUUID();

    /** The patient context of the token response, which nothing on our side may keep. */
    public final String patient = "epic-patient-" + UUID.randomUUID();

    /** The encounter context of the token response, which nothing on our side may keep. */
    public final String encounter = "epic-encounter-" + UUID.randomUUID();

    /** Every {@code id_token} this instance minted, in order. */
    public final List<String> idTokens = Collections.synchronizedList(new ArrayList<>());

    private FakeEpic(int port, String clientId, String activeKeyId, Supplier<JWKSet> ourJwks)
            throws IOException {
        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.issuer = "http://localhost:" + port + "/oauth2";
        this.clientId = clientId;
        this.activeKeyId = activeKeyId;
        this.ourJwks = ourJwks;
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("epic-signing-1")
                    .keyUse(KeyUse.SIGNATURE).generate();
        } catch (JOSEException impossible) {
            throw new IllegalStateException(impossible);
        }
        server.createContext("/oauth2/.well-known/openid-configuration", this::discovery);
        server.createContext("/oauth2/jwks", this::jwks);
        server.createContext("/oauth2/authorize", this::authorize);
        server.createContext("/oauth2/token", this::token);
        server.start();
    }

    /**
     * A fake Epic on {@code port}, holding our client assertions to {@code clientId} and
     * {@code activeKeyId} and verifying them against {@code ourJwks}.
     */
    public static FakeEpic start(
            int port, String clientId, String activeKeyId, Supplier<JWKSet> ourJwks)
            throws IOException {
        return new FakeEpic(port, clientId, activeKeyId, ourJwks);
    }

    /** The OIDC issuer, {@code APP_EPIC_OAUTH_ISSUER}. */
    public String issuer() {
        return issuer;
    }

    /** The token endpoint, as discovery reports it. */
    public String tokenEndpoint() {
        return issuer + "/token";
    }

    /** The {@code fhirUser} the next {@code id_token} carries. */
    public void signInAs(String fhirUser) {
        this.fhirUser = fhirUser;
    }

    /** Every {@code /authorize} request's query parameters, in order. */
    public List<Map<String, String>> authorizeRequests() {
        return List.copyOf(authorizeRequests);
    }

    /** Why {@code /token} refused each request it refused; empty when it refused none. */
    public List<String> tokenRefusals() {
        return List.copyOf(tokenRefusals);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void discovery(HttpExchange exchange) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", issuer);
        document.put("authorization_endpoint", issuer + "/authorize");
        document.put("token_endpoint", tokenEndpoint());
        document.put("jwks_uri", issuer + "/jwks");
        document.put("response_types_supported", List.of("code"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of("RS256"));
        document.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
        respond(exchange, 200, JSON.writeValueAsString(document));
    }

    private void jwks(HttpExchange exchange) throws IOException {
        respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString());
    }

    /** Remembers the request and answers as Epic does once the clinician has authorized. */
    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        authorizeRequests.add(query);
        String code = "code-" + UUID.randomUUID();
        issuedCodes.put(code, query);
        String location = query.get("redirect_uri")
                + "?code=" + encode(code) + "&state=" + encode(query.get("state"));
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parse(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String refusal = refusalOf(form);
        if (refusal != null) {
            tokenRefusals.add(refusal);
            respond(exchange, 400, JSON.writeValueAsString(Map.of(
                    "error", refusal.startsWith("assertion") ? "invalid_client" : "invalid_grant")));
            return;
        }
        Map<String, String> authorized = issuedCodes.get(form.get("code"));
        String idToken = idToken(authorized.get("nonce"));
        idTokens.add(idToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 3600);
        response.put("scope", "launch openid fhirUser");
        response.put("id_token", idToken);
        response.put("patient", patient);
        response.put("encounter", encounter);
        respond(exchange, 200, JSON.writeValueAsString(response));
    }

    /** Why Epic would refuse this redemption, or {@code null} when it would not. */
    private String refusalOf(Map<String, String> form) {
        if (!"authorization_code".equals(form.get("grant_type"))) {
            return "grant_type";
        }
        Map<String, String> authorized = issuedCodes.get(form.get("code"));
        if (authorized == null || !redeemedCodes.add(form.get("code"))) {
            return "code";
        }
        if (!authorized.get("redirect_uri").equals(form.get("redirect_uri"))) {
            return "redirect_uri";
        }
        if (!"S256".equals(authorized.get("code_challenge_method"))
                || form.get("code_verifier") == null
                || !authorized.get("code_challenge").equals(s256(form.get("code_verifier")))) {
            return "code_verifier";
        }
        if (!JWT_BEARER.equals(form.get("client_assertion_type"))) {
            return "assertion type";
        }
        return assertionRefusal(form.get("client_assertion"));
    }

    /** Verifies our client assertion against our own published JWKS, as Epic does. */
    private String assertionRefusal(String assertion) {
        try {
            SignedJWT jwt = SignedJWT.parse(assertion);
            if (!JWSAlgorithm.ES384.equals(jwt.getHeader().getAlgorithm())
                    || !activeKeyId.equals(jwt.getHeader().getKeyID())) {
                return "assertion header";
            }
            JWK key = ourJwks.get().getKeyByKeyId(activeKeyId);
            if (key == null || !jwt.verify(new ECDSAVerifier(((ECKey) key).toECPublicKey()))) {
                return "assertion signature";
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            Instant expiry = claims.getExpirationTime() == null
                    ? null : claims.getExpirationTime().toInstant();
            boolean claimsHold = clientId.equals(claims.getIssuer())
                    && clientId.equals(claims.getSubject())
                    && List.of(tokenEndpoint()).equals(claims.getAudience())
                    && claims.getJWTID() != null
                    && expiry != null
                    && expiry.isAfter(now)
                    && !expiry.isAfter(now.plus(Duration.ofMinutes(5)));
            return claimsHold ? null : "assertion claims";
        } catch (ParseException | JOSEException | RuntimeException malformed) {
            return "assertion malformed";
        }
    }

    private String idToken(String nonce) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("epic-subject-" + UUID.randomUUID())
                .audience(clientId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("nonce", nonce)
                .claim("fhirUser", fhirUser)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(signingKey.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (JOSEException impossible) {
            throw new IllegalStateException(impossible);
        }
        return jwt.serialize();
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A query string or form body, each name to its one value. */
    public static Map<String, String> parse(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            values.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** {@code location}'s query parameters. */
    public static Map<String, String> queryOf(URI location) {
        return parse(location.getRawQuery());
    }
}
