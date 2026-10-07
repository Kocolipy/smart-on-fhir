package com.example.backend.auth.epic;

import java.net.URI;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Epic's OIDC discovery document (flow step 2): where its authorization, token and JWKS
 * endpoints are, read from {@code {APP_EPIC_OAUTH_ISSUER}/.well-known/openid-configuration}
 * through the one outbound client, {@code epicRestClient} (D25).
 *
 * <p>Run on use, never at startup: password Login must not depend on Epic being reachable when
 * the application deploys (section 11). Every use reads the document afresh for now; the 24-hour
 * cache of a successful read (D26) is the outbound-resilience step's.
 *
 * <p>The document is accepted only for the issuer it was fetched from, as OpenID Connect
 * Discovery requires, so a document claiming another issuer cannot redirect the flow.
 */
public final class EpicProviderMetadata {

    /** Where OpenID Connect Discovery puts the document, relative to the issuer. */
    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private final RestClient epicRestClient;

    private final URI issuer;

    /**
     * @param epicRestClient the one outbound client every Epic call goes through (D25)
     * @param issuer         {@code APP_EPIC_OAUTH_ISSUER}
     */
    public EpicProviderMetadata(RestClient epicRestClient, URI issuer) {
        this.epicRestClient = epicRestClient;
        this.issuer = issuer;
    }

    /**
     * Epic's endpoints, as discovery reports them now.
     *
     * @throws IllegalStateException when the document is unreadable, names another issuer or
     *                               lacks an endpoint the flow needs
     */
    public Endpoints discover() {
        Map<?, ?> document = epicRestClient.get()
                .uri(issuer + DISCOVERY_PATH)
                .retrieve()
                .body(Map.class);
        if (document == null || !issuer.toString().equals(document.get("issuer"))) {
            throw new IllegalStateException("Epic discovery named another issuer");
        }
        return new Endpoints(
                issuer,
                required(document, "authorization_endpoint"),
                required(document, "token_endpoint"),
                required(document, "jwks_uri"));
    }

    private static URI required(Map<?, ?> document, String name) {
        if (!(document.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Epic discovery has no " + name);
        }
        return URI.create(value);
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
