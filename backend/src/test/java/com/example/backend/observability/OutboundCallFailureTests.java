package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.OutboundCallFailure.Reason;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0012's table for a failed outbound call, at the classifier both outbound adapters use: each
 * failure as spring-web 7 and the JDK client raise it — the cause chains below are the ones they
 * were seen to raise against a loopback server, the JSON parser's own failures raised by the
 * parser itself — with the client's timeout type and, for a call that times itself, how long it
 * had waited, or the status it answered; and the reason, {@code error.category} and {@code error.code} it is reported under.
 */
class OutboundCallFailureTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the failure is reported as. */
    private record Classified(Reason reason, ErrorCategory category, int code) {

        static Classified of(OutboundCallFailure failure) {
            return new Classified(failure.reason(), failure.category(), failure.code());
        }
    }

    private static final Classified TIMED_OUT =
            new Classified(Reason.TIMED_OUT, ErrorCategory.NETWORK, 502);

    private static final Classified UNREACHABLE =
            new Classified(Reason.UNREACHABLE, ErrorCategory.NETWORK, 502);

    private static final Classified UNREADABLE =
            new Classified(Reason.UNREADABLE, ErrorCategory.DATA, 502);

    // ---- the failures, as the client raises them ---------------------------------------------

    /** No headers within the read timeout: the JDK client's own timeout, under Spring's. */
    private static RestClientException headersLate() {
        return new ResourceAccessException("I/O error on GET request: Request cancelled",
                new HttpTimeoutException("Request cancelled"));
    }

    /**
     * No connection within the connect timeout: the JDK client's own timeout, a subtype of the
     * one it raises for the headers, under Spring's.
     */
    private static RestClientException connectTimedOut() {
        return new ResourceAccessException("I/O error on GET request: HTTP connect timed out",
                new HttpConnectTimeoutException("HTTP connect timed out"));
    }

    /** No connection at all: nothing listens on the port. */
    private static RestClientException nothingListening() {
        return new ResourceAccessException("I/O error on GET request: Connection refused",
                new ConnectException("Connection refused"));
    }

    /**
     * Headers in time, then a body read that failed: the JDK reports the read as {@code closed},
     * on what ended it, and the JSON parser wraps that I/O failure in one of its own.
     */
    private static RestClientException bodyReadEndedBy(IOException ended) {
        IOException closed = new IOException("closed",
                new IOException("fixed content-length: 100, bytes received: 5", ended));
        Throwable parsing = catchThrowable(() -> JSON.readValue(failingWith(closed), Map.class));
        return new RestClientException("Error while extracting response for type [Map]",
                new HttpMessageNotReadableException("JSON parse error: closed", parsing,
                        new MockHttpInputMessage(new byte[0])));
    }

    /** The body stopped arriving, and the read timeout closed its stream. */
    private static RestClientException bodyStall() {
        return bodyReadEndedBy(new IOException("subscription cancelled"));
    }

    /** The connection was reset mid-body. */
    private static RestClientException midBodyReset() {
        return bodyReadEndedBy(new SocketException("Connection reset"));
    }

    /** The connection was closed mid-body. */
    private static RestClientException midBodyDrop() {
        return bodyReadEndedBy(new EOFException("EOF reached while reading"));
    }

    /** A body read whole, as text, that stalled the same way: no parser between. */
    private static RestClientException textBodyStall() {
        return new RestClientException("Error while extracting response for type [String]",
                new IOException("closed", new IOException("subscription cancelled")));
    }

    /** A body that arrived whole and is not JSON. */
    private static RestClientException malformedBody() {
        Throwable parsing = catchThrowable(() -> JSON.readValue("not JSON: T7391046Q", Map.class));
        return new RestClientException("Error while extracting response for type [Map]",
                new HttpMessageNotReadableException("JSON parse error", parsing,
                        new MockHttpInputMessage(new byte[0])));
    }

    /** A body that is JSON but not the shape asked for: its converter refused it. */
    private static RestClientException wrongShape() {
        return new RestClientException("Error while extracting response",
                new HttpMessageNotReadableException(
                        "An error occurred reading the OAuth 2.0 Access Token Response",
                        new IllegalArgumentException("tokenValue cannot be empty"),
                        new MockHttpInputMessage(new byte[0])));
    }

    private static InputStream failingWith(IOException failure) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        };
    }

    // ---- the table -----------------------------------------------------------------------------

    /** The read timeout of the timed calls below. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Each failure with no status, from a call that times itself: how long it had waited when it
     * failed, against its read timeout. An I/O failure anywhere in the chain, the body's read
     * included, is no answer: timed out when the client's timeout is in the chain, or when the
     * call had waited its read timeout out, to the instant — the client cuts a body off then,
     * and the cut-off read fails as a reset's does — and unreachable otherwise. A failure to
     * parse what arrived is {@code data}, however long it took to arrive.
     */
    static Stream<Arguments> timedFailures() {
        return Stream.of(
                arguments("headers late", headersLate(), Duration.ofMillis(5_004), TIMED_OUT),
                arguments("no connection within the connect timeout", connectTimedOut(),
                        Duration.ofSeconds(2), TIMED_OUT),
                arguments("nothing listening", nothingListening(), Duration.ofMillis(1),
                        UNREACHABLE),
                arguments("a body stall", bodyStall(), Duration.ofMillis(5_003), TIMED_OUT),
                arguments("a body stall cut off at the read timeout to the instant", bodyStall(),
                        READ_TIMEOUT, TIMED_OUT),
                arguments("a text body stall", textBodyStall(), Duration.ofMillis(5_003),
                        TIMED_OUT),
                arguments("a mid-body reset", midBodyReset(), Duration.ofMillis(120),
                        UNREACHABLE),
                arguments("a mid-body reset just short of the read timeout", midBodyReset(),
                        READ_TIMEOUT.minusNanos(1), UNREACHABLE),
                arguments("a mid-body drop", midBodyDrop(), Duration.ofMillis(120), UNREACHABLE),
                arguments("a malformed body", malformedBody(), Duration.ofMillis(8), UNREADABLE),
                arguments("a malformed body, slow to arrive", malformedBody(),
                        Duration.ofSeconds(6), UNREADABLE),
                arguments("a body of the wrong shape", wrongShape(), Duration.ofMillis(8),
                        UNREADABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timedFailures")
    void aFailedCallIsReportedAsAdr0012sTableHasIt(String name, Throwable failure,
            Duration waited, Classified expected) {
        assertThat(Classified.of(OutboundCallFailure.failed(failure, HttpTimeoutException.class,
                waited, READ_TIMEOUT))).isEqualTo(expected);
    }

    /**
     * Each failure with no status, from a call that does not time itself: timed out only when
     * the client's timeout is in the chain. A body the read timeout cut off then fails as a reset
     * does, so it is unreachable: no answer, {@code network}, all the same.
     */
    static Stream<Arguments> untimedFailures() {
        return Stream.of(
                arguments("headers late", headersLate(), TIMED_OUT),
                arguments("no connection within the connect timeout", connectTimedOut(),
                        TIMED_OUT),
                arguments("nothing listening", nothingListening(), UNREACHABLE),
                arguments("a body stall", bodyStall(), UNREACHABLE),
                arguments("a mid-body reset", midBodyReset(), UNREACHABLE),
                arguments("a malformed body", malformedBody(), UNREADABLE),
                arguments("a body of the wrong shape", wrongShape(), UNREADABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("untimedFailures")
    void aFailedCallThatIsNotTimedIsReportedAsAdr0012sTableHasIt(String name, Throwable failure,
            Classified expected) {
        assertThat(Classified.of(OutboundCallFailure.failed(failure, HttpTimeoutException.class)))
                .isEqualTo(expected);
    }

    /**
     * Each error status, under its own code: a {@code 5xx} the other service's failure, Epic's
     * {@code 401} our credential refused, and any other an answer the service cannot use.
     */
    static Stream<Arguments> statuses() {
        return Stream.of(
                arguments(503, new Classified(Reason.SERVER_ERROR, ErrorCategory.SERVER, 503)),
                arguments(500, new Classified(Reason.SERVER_ERROR, ErrorCategory.SERVER, 500)),
                arguments(401,
                        new Classified(Reason.CREDENTIAL_REFUSED, ErrorCategory.CERT_AUTH, 401)),
                arguments(499, new Classified(Reason.UNEXPECTED_STATUS, ErrorCategory.DATA, 499)),
                arguments(400, new Classified(Reason.UNEXPECTED_STATUS, ErrorCategory.DATA, 400)),
                arguments(403, new Classified(Reason.UNEXPECTED_STATUS, ErrorCategory.DATA, 403)));
    }

    @ParameterizedTest(name = "a {0}")
    @MethodSource("statuses")
    void anErrorStatusIsReportedAsAdr0012sTableHasIt(int status, Classified expected) {
        assertThat(Classified.of(OutboundCallFailure.answered(status))).isEqualTo(expected);
    }
}
