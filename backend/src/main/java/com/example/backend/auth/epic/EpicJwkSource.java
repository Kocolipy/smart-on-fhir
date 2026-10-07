package com.example.backend.auth.epic;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Epic's {@code id_token} signing keys (flow step 4), fetched from the discovered
 * {@code jwks_uri} through {@code epicRestClient} (D25) — never through a client of the JWT
 * library's own, which would sit outside the shared timeouts, tracing and logging.
 *
 * <p>One for the application, so the keys fetched for one Login serve the next. They are kept for
 * 24 hours, as discovery's document is, or until discovery names another {@code jwks_uri}. An
 * {@code id_token} whose {@code kid} the kept keys do not hold is never accepted from them: the
 * JWKS is refetched up to 3 times, after 1, 2 and 4 seconds, each refetch logged at {@code WARN}
 * with its attempt number, so a key Epic has just published is found. If it is still unknown the
 * {@code id_token} is refused — no key matches it — with one {@code ERROR}, and discovery is
 * refetched, in case Epic moved its keys (D26).
 *
 * <p>A fetch that fails is not retried: Epic being unavailable leaves as an
 * {@link EpicOutboundException}, as the cause of the key-source failure the decoder reports, and
 * the Login lands at the unavailable notice.
 */
public final class EpicJwkSource implements JWKSource<SecurityContext> {

    /** How long fetched keys are kept: as long as discovery's document is. */
    static final Duration LIFETIME = EpicProviderMetadata.LIFETIME;

    /** The waits before D26's refetches, in order: one refetch after each. */
    static final List<Duration> REFETCH_AFTER =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    private static final Logger log = LoggerFactory.getLogger(EpicJwkSource.class);

    private final RestClient epicRestClient;

    private final EpicProviderMetadata metadata;

    private final Clock clock;

    private final EpicRetryPause pause;

    /** The last keys fetched, where from and when, or {@code null} before the first fetch. */
    private volatile Fetched fetched;

    /**
     * @param epicRestClient the one outbound client every Epic call goes through (D25)
     * @param metadata       discovery, for the {@code jwks_uri} and D26's rediscovery
     * @param clock          what fetched keys' age is measured by
     * @param pause          the wait before each refetch
     */
    public EpicJwkSource(RestClient epicRestClient, EpicProviderMetadata metadata, Clock clock,
            EpicRetryPause pause) {
        this.epicRestClient = epicRestClient;
        this.metadata = metadata;
        this.clock = clock;
        this.pause = pause;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        URI jwksUri = jwksUri();
        Fetched current = fetched;
        if (current == null || !current.from().equals(jwksUri)
                || !clock.instant().isBefore(current.at().plus(LIFETIME))) {
            current = fetch(jwksUri);
        }
        List<JWK> matched = selector.select(current.keys());
        for (int attempt = 1; matched.isEmpty() && attempt <= REFETCH_AFTER.size(); attempt++) {
            waitBefore(attempt);
            LogEvent.jwksRefetchWarning(log, attempt)
                    .addKeyValue(LogEvent.EPIC_CALL, EpicOutboundCall.JWKS.tag())
                    .log();
            matched = selector.select(fetch(jwksUri).keys());
        }
        if (matched.isEmpty()) {
            // A kid still unknown after the refetches: Epic's keys gave no usable answer.
            LogEvent.error(log, Operation.EPIC_JWKS_REFETCH, LogEvent.BAD_GATEWAY_ERROR_CODE,
                            ErrorCategory.DATA, true, Category.NETWORK, Type.ERROR)
                    .addKeyValue(LogEvent.EPIC_CALL, EpicOutboundCall.JWKS.tag())
                    .log();
            metadata.rediscover();
        }
        return matched;
    }

    private URI jwksUri() throws KeySourceException {
        try {
            return metadata.discover().jwksUri();
        } catch (EpicOutboundException failed) {
            throw new KeySourceException("Epic's discovery document could not be read", failed);
        }
    }

    private void waitBefore(int attempt) throws KeySourceException {
        try {
            pause.pause(REFETCH_AFTER.get(attempt - 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KeySourceException("Interrupted before refetching Epic's JWKS");
        }
    }

    /** Fetches the JWKS and keeps it; a failure keeps nothing new. */
    private Fetched fetch(URI jwksUri) throws KeySourceException {
        JWKSet keys;
        try {
            String document = EpicOutboundCall.JWKS.on(epicRestClient.get().uri(jwksUri))
                    .retrieve()
                    .body(String.class);
            keys = JWKSet.parse(document == null ? "" : document);
        } catch (RestClientException failed) {
            // The type only: neither the document nor the URI's query belongs in a trace.
            throw new KeySourceException("Epic's JWKS could not be read",
                    EpicOutboundException.of(EpicOutboundCall.JWKS, failed));
        } catch (ParseException malformed) {
            throw new KeySourceException("Epic's JWKS could not be read",
                    EpicOutboundException.malformed(EpicOutboundCall.JWKS));
        }
        Fetched now = new Fetched(jwksUri, keys, clock.instant());
        fetched = now;
        return now;
    }

    /** Keys fetched, where from and when. */
    private record Fetched(URI from, JWKSet keys, Instant at) {
    }
}
