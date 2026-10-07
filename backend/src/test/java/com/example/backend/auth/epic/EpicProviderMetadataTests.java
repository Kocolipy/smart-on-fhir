package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.backend.observability.LogEvent.ErrorCategory;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Discovery on first use, kept for 24 hours, only when it succeeded (D26), against a
 * {@link FakeEpic} over real HTTP, with time from a clock the test moves.
 */
class EpicProviderMetadataTests {

    private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");

    private final MovableClock clock = new MovableClock(START);

    private FakeEpic epic;

    private EpicProviderMetadata metadata;

    @BeforeEach
    void setUp() throws IOException {
        epic = FakeEpic.start(freePort(), "epic-client-id", "active-kid", JWKSet::new);
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        requests.setReadTimeout(Duration.ofMillis(500));
        metadata = new EpicProviderMetadata(
                RestClient.builder().requestFactory(requests).build(),
                URI.create(epic.issuer()), clock);
    }

    @AfterEach
    void tearDown() {
        epic.close();
    }

    /** Section 11: startup — which is when this is built — does not contact Epic. */
    @Test
    void buildingItContactsEpicNotAtAll() {
        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isZero();
    }

    @Test
    void theFirstUseReadsEpicsEndpoints() {
        assertThat(metadata.discover()).isEqualTo(new EpicProviderMetadata.Endpoints(
                URI.create(epic.issuer()),
                URI.create(epic.issuer() + "/authorize"),
                URI.create(epic.issuer() + "/token"),
                URI.create(epic.issuer() + "/jwks?" + FakeEpic.JWKS_QUERY)));
    }

    @Test
    void aUseWithin24HoursAnswersWithWhatWasRead() {
        EpicProviderMetadata.Endpoints read = metadata.discover();
        epic.discoveryWith("token_endpoint", epic.issuer() + "/moved-token");
        clock.advance(Duration.ofHours(23));

        assertThat(metadata.discover()).isEqualTo(read);
    }

    @Test
    void aUseWithin24HoursReadsNothingMore() {
        metadata.discover();
        clock.advance(Duration.ofHours(24).minusSeconds(1));

        metadata.discover();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(1);
    }

    @Test
    void aUse24HoursLaterReadsTheDocumentAgain() {
        metadata.discover();
        clock.advance(Duration.ofHours(24));

        metadata.discover();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(2);
    }

    @Test
    void aFailedReadIsNotKept() {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        catchThrowableOfType(EpicOutboundException.class, metadata::discover);
        epic.answering(FakeEpic.Endpoint.DISCOVERY);

        metadata.discover();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(2);
    }

    @Test
    void aReadAfterAFailureIsKept() {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        catchThrowableOfType(EpicOutboundException.class, metadata::discover);
        epic.answering(FakeEpic.Endpoint.DISCOVERY);
        metadata.discover();

        metadata.discover();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(2);
    }

    @Test
    void aTimeoutIsEpicUnavailableUnderTheNetworkCategory() {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);

        EpicOutboundException failure =
                catchThrowableOfType(EpicOutboundException.class, metadata::discover);

        assertThat(failure).returns(true, EpicOutboundException::unavailable)
                .returns(ErrorCategory.NETWORK, EpicOutboundException::category)
                .returns(EpicOutboundCall.DISCOVERY, EpicOutboundException::call);
    }

    @Test
    void a5xxIsEpicUnavailableUnderTheServerCategoryWithItsStatus() {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);

        EpicOutboundException failure =
                catchThrowableOfType(EpicOutboundException.class, metadata::discover);

        assertThat(failure).returns(true, EpicOutboundException::unavailable)
                .returns(ErrorCategory.SERVER, EpicOutboundException::category)
                .returns(503, EpicOutboundException::code);
    }

    /** Epic answered, with no document: not unavailable, but an error to follow up. */
    @Test
    void aMalformedDocumentIsDataNotUnavailable() {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.MALFORMED);

        EpicOutboundException failure =
                catchThrowableOfType(EpicOutboundException.class, metadata::discover);

        assertThat(failure).returns(false, EpicOutboundException::unavailable)
                .returns(ErrorCategory.DATA, EpicOutboundException::category);
    }

    /** OpenID Connect Discovery: a document is accepted only for the issuer it was read from. */
    @Test
    void aDocumentNamingAnotherIssuerIsData() {
        epic.discoveryWith("issuer", "https://elsewhere.example.org/oauth2");

        assertThat(catchThrowableOfType(EpicOutboundException.class, metadata::discover))
                .returns(ErrorCategory.DATA, EpicOutboundException::category);
    }

    @Test
    void anEmptyAnswerIsData() {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.EMPTY);

        assertThat(catchThrowableOfType(EpicOutboundException.class, metadata::discover))
                .returns(ErrorCategory.DATA, EpicOutboundException::category);
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization_endpoint", "token_endpoint", "jwks_uri"})
    void aDocumentLackingAnEndpointIsData(String member) {
        epic.discoveryWith(member, null);

        assertThat(catchThrowableOfType(EpicOutboundException.class, metadata::discover))
                .returns(ErrorCategory.DATA, EpicOutboundException::category);
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization_endpoint", "token_endpoint", "jwks_uri"})
    void aDocumentWithAnEmptyEndpointIsData(String member) {
        // Empty, not merely blank: an empty string is a well-formed (relative) URI, so only the
        // blank check refuses it.
        epic.discoveryWith(member, "");

        assertThat(catchThrowableOfType(EpicOutboundException.class, metadata::discover))
                .returns(ErrorCategory.DATA, EpicOutboundException::category);
    }

    @Test
    void aDocumentWhoseEndpointIsNoUriIsData() {
        epic.discoveryWith("token_endpoint", "https://epic example.org/token");

        assertThat(catchThrowableOfType(EpicOutboundException.class, metadata::discover))
                .returns(ErrorCategory.DATA, EpicOutboundException::category);
    }

    /** D26: an id_token key still unknown after the refetches reads discovery again at once. */
    @Test
    void rediscoveringReadsTheDocumentAgainWithin24Hours() {
        metadata.discover();

        metadata.rediscover();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(2);
    }

    @Test
    void aFailedRediscoveryKeepsNothing() {
        metadata.discover();
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        metadata.rediscover();
        epic.answering(FakeEpic.Endpoint.DISCOVERY);

        metadata.discover();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(3);
    }

    /** A clock the test moves forward. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static int freePort() {
        // Test-only: binds an ephemeral local port just to learn a free number for the fake
        // Epic, and closes at once. Nothing is ever sent over it, so there is no traffic for
        // TLS to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
