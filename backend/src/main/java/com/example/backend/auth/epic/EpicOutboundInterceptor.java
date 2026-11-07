package com.example.backend.auth.epic;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RedactedFaultException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The one interceptor on {@code epicRestClient} (ADR 0012, D25): every Epic call is
 * logged, measured and traced here, whichever call site made it.
 *
 * <ul>
 *   <li><b>Log.</b> "Epic outbound call started" and "Epic outbound call completed" at
 *       {@code INFO}, with the {@code call} — {@code discovery}, {@code jwks} or {@code token},
 *       as its call site named it — the method, where it went with no query, the status and
 *       {@code event.duration_ms}. A call that got no answer — a timeout, no connection — is
 *       "Epic outbound call failed" at {@code ERROR}, with its {@code network} category and the
 *       failure's stack under its type name alone; and so is one whose body stopped arriving or
 *       whose connection dropped once the headers were in, after its "completed", when the read
 *       of the body fails. Neither a body nor a header is ever read here: the body is watched
 *       only for a read that fails.
 *   <li><b>Metrics.</b> The {@code epic.outbound} timer, timed to the headers, and the
 *       {@code epic.outbound.errors} counter of calls that got no answer, the whole body
 *       included, or a {@code 5xx} — Epic being unavailable — both tagged {@code call}. A counter of its own name, because Prometheus allows one type per
 *       metric name.
 *   <li><b>Trace.</b> The call carries a W3C {@code traceparent} naming the span it was made
 *       in. This deployment installs no propagator — an inbound {@code traceparent} is ignored
 *       (ADR 0003) — so the outbound one is written here, for Epic's side of the trace.
 * </ul>
 */
public final class EpicOutboundInterceptor implements ClientHttpRequestInterceptor {

    /** The timer's name, and the stem of the error counter's. */
    static final String OUTBOUND = "epic.outbound";

    /** The error counter's name. */
    static final String OUTBOUND_ERRORS = OUTBOUND + ".errors";

    /** The W3C Trace Context header. */
    static final String TRACEPARENT = "traceparent";

    private static final Logger log = LoggerFactory.getLogger(EpicOutboundInterceptor.class);

    private final MeterRegistry registry;

    private final Tracer tracer;

    /**
     * @param registry where the {@code epic.outbound} meters are registered
     * @param tracer   the tracer whose current span a call is made in
     */
    public EpicOutboundInterceptor(MeterRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
        // Every series from the start, at zero: an alert on the error count's increase then
        // sees the first error too, which a series appearing at 1 would hide from it.
        for (EpicOutboundCall call : EpicOutboundCall.values()) {
            timer(call.tag());
            errors(call.tag());
        }
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String call = EpicOutboundCall.of(request)
                .orElseThrow(() -> new IllegalStateException(
                        "An Epic call was made without naming which call it is"))
                .tag();
        String method = request.getMethod().name();
        String url = withoutQuery(request.getURI());
        traceparent(request);
        LogEvent.outboundStart(log, call, method, url).log();
        long started = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException noAnswer) {
            long elapsed = System.nanoTime() - started;
            measure(call, elapsed, true);
            logFailed(call, method, url, elapsed, noAnswer);
            throw noAnswer;
        }
        long elapsed = System.nanoTime() - started;
        int status = response.getStatusCode().value();
        measure(call, elapsed, status >= 500);
        LogEvent.outboundEnd(log, call, method, url, status,
                        TimeUnit.NANOSECONDS.toMillis(elapsed))
                .log();
        return new BodyWatched(response, noBody -> {
            errors(call).increment();
            logFailed(call, method, url, System.nanoTime() - started, noBody);
        });
    }

    private static void logFailed(String call, String method, String url, long elapsedNanos,
            IOException noAnswer) {
        LogEvent.outboundFailed(log, call, method, url,
                        TimeUnit.NANOSECONDS.toMillis(elapsedNanos))
                .setCause(RedactedFaultException.of(noAnswer, noAnswer.getClass().getName()))
                .log();
    }

    private void measure(String call, long elapsedNanos, boolean unavailable) {
        timer(call).record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (unavailable) {
            errors(call).increment();
        }
    }

    /** The registry hands back the one timer per call, registering it once. */
    private Timer timer(String call) {
        return Timer.builder(OUTBOUND)
                .description("Epic outbound calls, by call")
                .tag("call", call)
                .register(registry);
    }

    private Counter errors(String call) {
        return Counter.builder(OUTBOUND_ERRORS)
                .description("Epic outbound calls that got no answer or a 5xx, by call")
                .tag("call", call)
                .register(registry);
    }

    /** The span the call is made in, as the W3C header names it, unless the call has one. */
    private void traceparent(HttpRequest request) {
        Span current = tracer.currentSpan();
        if (current == null || request.getHeaders().containsHeader(TRACEPARENT)) {
            return;
        }
        TraceContext context = current.context();
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        request.getHeaders().set(TRACEPARENT,
                "00-" + context.traceId() + "-" + context.spanId() + "-" + flags);
    }

    /**
     * A response whose body, read by whoever reads it, reports the first read that fails with an
     * I/O failure to {@code noBody} before the failure goes on: the answer stopped arriving, or
     * its connection dropped, once the headers were in. What is read passes through untouched,
     * and nothing of it is kept.
     */
    private static final class BodyWatched implements ClientHttpResponse {

        private final ClientHttpResponse response;

        private final Consumer<IOException> noBody;

        private final AtomicBoolean reported = new AtomicBoolean();

        BodyWatched(ClientHttpResponse response, Consumer<IOException> noBody) {
            this.response = response;
            this.noBody = noBody;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return new FilterInputStream(response.getBody()) {
                @Override
                public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException failed) {
                        throw reported(failed);
                    }
                }

                @Override
                public int read(byte[] into, int offset, int length) throws IOException {
                    try {
                        return super.read(into, offset, length);
                    } catch (IOException failed) {
                        throw reported(failed);
                    }
                }
            };
        }

        @Override
        public void close() {
            response.close();
        }

        private IOException reported(IOException failed) {
            if (reported.compareAndSet(false, true)) {
                noBody.accept(failed);
            }
            return failed;
        }
    }

    /** Scheme, host, port and path: never the query, the fragment or any user info. */
    static String withoutQuery(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                    null, null).toString();
        } catch (URISyntaxException unrepresentable) {
            return uri.getScheme() + "://" + uri.getHost();
        }
    }
}
