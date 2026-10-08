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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 *
 * <p>It can also be Epic on a bad day: any of discovery, the JWKS and {@code /token} can be made
 * to stall past our read timeout, answer {@code 503}, or answer {@code 200} with a body that is
 * not what was asked for ({@link #failing}). Each endpoint counts the requests it was sent, so a
 * test can hold us to exactly one token call, or to D26's JWKS refetches. Its signing key can be
 * rotated, the new key published only from a later JWKS fetch on ({@link #rotateSigningKey}).
 * The JWKS URI it advertises carries a query, so a test can show none reaches our log. Requests
 * are served on a pool of their own, so a stalled one holds up nothing else.
 */
public final class FakeEpic implements AutoCloseable {

    /** The token endpoint's assertion type, RFC 7523. */
    private static final String JWT_BEARER =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The query on the JWKS URI discovery advertises. */
    public static final String JWKS_QUERY = "release=current-fake-epic-query";

    /** The endpoints a test can count and fail. */
    public enum Endpoint { DISCOVERY, JWKS, TOKEN }

    /** How an endpoint fails when a test asks it to. */
    public enum Failure {
        /** Answers nothing for longer than our read timeout in tests. */
        STALL,
        /** Answers {@code 503}, as Epic does when it is down for maintenance. */
        SERVER_ERROR,
        /** Answers {@code 200} with a body that is no document of the kind asked for. */
        MALFORMED,
        /** Answers {@code 200} with no body at all. */
        EMPTY
    }

    /** How long a stalled endpoint waits before answering, past every test's read timeout. */
    public static final Duration STALL_FOR = Duration.ofSeconds(3);

    private final HttpServer server;

    private final ExecutorService requests = Executors.newCachedThreadPool(work -> {
        Thread thread = new Thread(work, "fake-epic");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Endpoint, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    private final Map<Endpoint, Failure> failures = new ConcurrentHashMap<>();

    private final Map<String, Optional<String>> discoveryChanges = new ConcurrentHashMap<>();

    private final List<Map<String, List<String>>> tokenRequestHeaders =
            Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger jwksFetchesSinceRotation = new AtomicInteger();

    private volatile RSAKey publishedKey;

    private volatile int newKeyPublishedFromFetch;

    private volatile boolean rejectingOurAssertion;

    private final String issuer;

    private final String clientId;

    private final String activeKeyId;

    private final Supplier<JWKSet> ourJwks;

    private volatile RSAKey signingKey;

    private final Map<String, Map<String, String>> issuedCodes = new ConcurrentHashMap<>();

    private final Set<String> redeemedCodes = Collections.synchronizedSet(new HashSet<>());

    private final List<String> tokenRefusals = Collections.synchronizedList(new ArrayList<>());

    private final List<Map<String, String>> authorizeRequests =
            Collections.synchronizedList(new ArrayList<>());

    private volatile String fhirUser;

    private final List<Map<String, String>> tokenForms =
            Collections.synchronizedList(new ArrayList<>());

    private final Map<String, String> rememberedAtAuthorize = new ConcurrentHashMap<>();

    private volatile String authorizeError;

    private volatile Duration tokenDelay = Duration.ZERO;

    private volatile Consumer<JWTClaimsSet.Builder> idTokenClaims = claims -> { };

    private volatile JWSAlgorithm idTokenAlgorithm = JWSAlgorithm.RS256;

    private volatile boolean forgingIdTokenSignatures;

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
        this.signingKey = newSigningKey();
        this.publishedKey = signingKey;
        server.createContext("/oauth2/.well-known/openid-configuration",
                exchange -> serve(Endpoint.DISCOVERY, exchange, this::discovery));
        server.createContext("/oauth2/jwks", exchange -> serve(Endpoint.JWKS, exchange, this::jwks));
        server.createContext("/oauth2/authorize", this::authorize);
        server.createContext("/oauth2/token", exchange -> serve(Endpoint.TOKEN, exchange, this::token));
        server.setExecutor(requests);
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

    /** From now on {@code endpoint} fails as {@code failure}. */
    public void failing(Endpoint endpoint, Failure failure) {
        failures.put(endpoint, failure);
    }

    /**
     * From now on the discovery document carries {@code value} as {@code member}, or lacks the
     * member altogether for {@code null}.
     */
    public void discoveryWith(String member, String value) {
        discoveryChanges.put(member, Optional.ofNullable(value));
    }

    /** From now on {@code endpoint} answers as Epic does on a good day. */
    public void answering(Endpoint endpoint) {
        failures.remove(endpoint);
    }

    /** How many requests {@code endpoint} has been sent, including any it failed. */
    public int requests(Endpoint endpoint) {
        return requestCounts.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).get();
    }

    /** Every {@code /token} request's headers, in order, by lower-case name. */
    public List<Map<String, List<String>>> tokenRequestHeaders() {
        return List.copyOf(tokenRequestHeaders);
    }

    /**
     * Signs every {@code id_token} from now on with a new key under a new {@code kid}, which the
     * JWKS publishes only from its {@code publishedFromFetch}-th fetch after this call on — and
     * never, for {@link Integer#MAX_VALUE}. Until then it keeps publishing the old key alone.
     */
    public void rotateSigningKey(int publishedFromFetch) {
        jwksFetchesSinceRotation.set(0);
        newKeyPublishedFromFetch = publishedFromFetch;
        signingKey = newSigningKey();
    }

    /** From now on {@code /token} refuses our client assertion, as Epic does a key it does not know. */
    public void rejectingOurAssertion() {
        rejectingOurAssertion = true;
    }

    /**
     * Every {@code /token} request's form, in order. Test-only: the fake is Epic, the one party
     * that is sent the code and the verifier.
     */
    public List<Map<String, String>> tokenRequests() {
        return List.copyOf(tokenForms);
    }

    /**
     * From now on {@code /authorize} remembers {@code value} as the request's {@code parameter}
     * instead of what it was sent, so {@code /token} holds the redemption to it: a
     * {@code redirect_uri} or a {@code code_challenge} our token call cannot match.
     */
    public void rememberingAtAuthorize(String parameter, String value) {
        rememberedAtAuthorize.put(parameter, value);
    }

    /** From now on {@code /authorize} answers with the OAuth {@code error}, and no code. */
    public void answeringAuthorizeWithError(String error) {
        authorizeError = error;
    }

    /** From now on {@code /token} waits {@code delay} before it answers. */
    public void slowingTokenBy(Duration delay) {
        tokenDelay = delay;
    }

    /** From now on every {@code id_token}'s claims are what they were, then {@code change}d. */
    public void mintingIdTokensWith(Consumer<JWTClaimsSet.Builder> change) {
        idTokenClaims = change;
    }

    /** From now on every {@code id_token} is signed with {@code algorithm}, by the same RSA key. */
    public void signingIdTokensWith(JWSAlgorithm algorithm) {
        idTokenAlgorithm = algorithm;
    }

    /**
     * From now on every {@code id_token} is signed by a key Epic never published, under the
     * {@code kid} of the one it did: a forged signature.
     */
    public void forgingIdTokenSignatures() {
        forgingIdTokenSignatures = true;
    }

    /** Why {@code /token} refused each request it refused; empty when it refused none. */
    public List<String> tokenRefusals() {
        return List.copyOf(tokenRefusals);
    }

    @Override
    public void close() {
        server.stop(0);
        requests.shutdownNow();
    }

    /** One request to {@code endpoint}: counted, then failed as the test asked, or answered. */
    private void serve(Endpoint endpoint, HttpExchange exchange, Handler handler)
            throws IOException {
        requestCounts.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).incrementAndGet();
        if (endpoint == Endpoint.TOKEN) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach(
                    (name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT),
                            List.copyOf(values)));
            tokenRequestHeaders.add(headers);
        }
        Failure failure = failures.get(endpoint);
        if (failure == null) {
            handler.handle(exchange);
            return;
        }
        switch (failure) {
            case STALL -> {
                try {
                    Thread.sleep(STALL_FOR);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            }
            case SERVER_ERROR -> respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
            case MALFORMED -> respond(exchange, 200, "{\"not\":\"what was asked for\"}");
            case EMPTY -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        }
    }

    /** An endpoint's handler. */
    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static RSAKey newSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("epic-signing-" + UUID.randomUUID())
                    .keyUse(KeyUse.SIGNATURE).generate();
        } catch (JOSEException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void discovery(HttpExchange exchange) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", issuer);
        document.put("authorization_endpoint", issuer + "/authorize");
        document.put("token_endpoint", tokenEndpoint());
        // A query, as a real deployment's JWKS URI may carry, which our log must never show.
        document.put("jwks_uri", issuer + "/jwks?" + JWKS_QUERY);
        document.put("response_types_supported", List.of("code"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of("RS256"));
        document.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
        discoveryChanges.forEach((member, value) -> {
            if (value.isPresent()) {
                document.put(member, value.get());
            } else {
                document.remove(member);
            }
        });
        respond(exchange, 200, JSON.writeValueAsString(document));
    }

    private void jwks(HttpExchange exchange) throws IOException {
        if (signingKey != publishedKey
                && jwksFetchesSinceRotation.incrementAndGet() >= newKeyPublishedFromFetch) {
            publishedKey = signingKey;
        }
        respond(exchange, 200, new JWKSet(publishedKey.toPublicJWK()).toString());
    }

    /** Remembers the request and answers as Epic does once the clinician has authorized. */
    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        authorizeRequests.add(query);
        String location;
        if (authorizeError != null) {
            location = query.get("redirect_uri") + "?error=" + encode(authorizeError)
                    + "&state=" + encode(query.get("state"));
        } else {
            String code = "code-" + UUID.randomUUID();
            Map<String, String> remembered = new LinkedHashMap<>(query);
            remembered.putAll(rememberedAtAuthorize);
            issuedCodes.put(code, remembered);
            location = query.get("redirect_uri")
                    + "?code=" + encode(code) + "&state=" + encode(query.get("state"));
        }
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parse(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenForms.add(form);
        if (tokenDelay.isPositive()) {
            try {
                Thread.sleep(tokenDelay);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        String refusal = rejectingOurAssertion ? "assertion rejected" : refusalOf(form);
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
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("epic-subject-" + UUID.randomUUID())
                .audience(clientId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("nonce", nonce)
                .claim("fhirUser", fhirUser);
        idTokenClaims.accept(claims);
        RSAKey key = signingKey;
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(idTokenAlgorithm)
                .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(forgingIdTokenSignatures ? newSigningKey() : key));
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
