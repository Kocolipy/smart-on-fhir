package com.example.backend.auth.epic;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Epic's OIDC discovery document (flow step 2): where its authorization, token and JWKS
 * endpoints are, read from {@code {APP_EPIC_OAUTH_ISSUER}/.well-known/openid-configuration}
 * through the one outbound client, {@code epicRestClient} (D25).
 *
 * <p>Run on first use, never at startup: password Login must not depend on Epic being reachable
 * when the application deploys (ADR 0013's deviation "discovery on first use"). A successful
 * read is then kept for 24 hours, by the injected {@link Clock}; a failed one is kept for no time
 * at all, so the next launch tries again (D26). A read that failed because Epic was unavailable —
 * no answer in time, or a {@code 5xx} — is reported as such ({@link EpicOutboundException}), and
 * the launch lands at the unavailable notice.
 *
 * <p>The document is accepted only for the issuer it was fetched from, as OpenID Connect
 * Discovery requires, so a document claiming another issuer cannot redirect the flow.
 */
public final class EpicProviderMetadata {

    /** How long a successful read is kept (D26). */
    static final Duration LIFETIME = Duration.ofHours(24);

    /** Where OpenID Connect Discovery puts the document, relative to the issuer. */
    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private final RestClient epicRestClient;

    private final URI issuer;

    private final Clock clock;

    /** The last successful read and when it was made, or {@code null} before the first. */
    private volatile Discovered discovered;

    /**
     * @param epicRestClient the one outbound client every Epic call goes through (D25)
     * @param issuer         {@code APP_EPIC_OAUTH_ISSUER}
     * @param clock          what a read's age is measured by
     */
    public EpicProviderMetadata(RestClient epicRestClient, URI issuer, Clock clock) {
        this.epicRestClient = epicRestClient;
        this.issuer = issuer;
        this.clock = clock;
    }

    /**
     * Epic's endpoints: as last read, when that was less than 24 hours ago, and otherwise as
     * discovery reports them now.
     *
     * @throws EpicOutboundException when the document cannot be read — Epic unavailable, or an
     *                               answer that is no document, names another issuer or lacks an
     *                               endpoint the flow needs
     */
    public Endpoints discover() {
        Discovered current = discovered;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.at().plus(LIFETIME))) {
            return current.endpoints();
        }
        Endpoints endpoints = read();
        discovered = new Discovered(endpoints, now);
        return endpoints;
    }

    /**
     * Drops the kept read and reads the document again at once: D26's response to an
     * {@code id_token} signed by a key Epic's JWKS still does not publish. A read that fails here
     * is not this call's failure — the {@code id_token} is refused either way — and leaves nothing
     * kept, so the next launch reads again.
     */
    public void rediscover() {
        discovered = null;
        try {
            discover();
        } catch (EpicOutboundException unread) {
            // Logged by the outbound interceptor; nothing is kept, so the next use reads again.
        }
    }

    private Endpoints read() {
        Map<?, ?> document;
        try {
            document = EpicOutboundCall.DISCOVERY.on(epicRestClient.get()
                            .uri(issuer + DISCOVERY_PATH))
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException failed) {
            throw EpicOutboundException.of(EpicOutboundCall.DISCOVERY, failed);
        }
        if (document == null || !issuer.toString().equals(document.get("issuer"))) {
            throw EpicOutboundException.malformed(EpicOutboundCall.DISCOVERY);
        }
        return new Endpoints(
                issuer,
                required(document, "authorization_endpoint"),
                required(document, "token_endpoint"),
                required(document, "jwks_uri"));
    }

    private static URI required(Map<?, ?> document, String name) {
        if (!(document.get(name) instanceof String value) || value.isBlank()) {
            throw EpicOutboundException.malformed(EpicOutboundCall.DISCOVERY);
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException notAUri) {
            throw EpicOutboundException.malformed(EpicOutboundCall.DISCOVERY);
        }
    }

    /** A successful read, and when it was made. */
    private record Discovered(Endpoints endpoints, Instant at) {
    }

    /**
     * The endpoints an Epic Login uses.
     *
     * @param issuer                the issuer they were discovered on, the {@code id_token}'s
     *                              {@code iss}
     * @param authorizationEndpoint where the browser is sent to authorize
     * @param tokenEndpoint         where the code is redeemed, our client assertion's
     *                              {@code aud}
     * @param jwksUri               where Epic's {@code id_token} signing keys are published
     */
    public record Endpoints(
            URI issuer, URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri) {
    }
}
