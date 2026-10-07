package com.example.backend.auth.epic;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.text.ParseException;
import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Epic's {@code id_token} signing keys (flow step 4), fetched from the discovered
 * {@code jwks_uri} through {@code epicRestClient} (D25) — never through a client of the JWT
 * library's own, which would sit outside the shared timeouts, tracing and logging.
 *
 * <p>Read each time an {@code id_token} is verified, so a key Epic has just published is found.
 * The cache and the D26 refetch on an unknown {@code kid} are the outbound-resilience step's.
 */
public final class EpicJwkSource implements JWKSource<SecurityContext> {

    private final RestClient epicRestClient;

    private final URI jwksUri;

    public EpicJwkSource(RestClient epicRestClient, URI jwksUri) {
        this.epicRestClient = epicRestClient;
        this.jwksUri = jwksUri;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        try {
            String document = epicRestClient.get().uri(jwksUri).retrieve().body(String.class);
            return selector.select(JWKSet.parse(document == null ? "" : document));
        } catch (RestClientException | ParseException unavailable) {
            // The type only: neither the document nor the URI's query belongs in a trace.
            throw new KeySourceException("Epic's JWKS could not be read");
        }
    }
}
