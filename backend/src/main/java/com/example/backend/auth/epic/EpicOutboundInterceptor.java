package com.example.backend.auth.epic;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RedactedFaultException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The one interceptor on {@code epicRestClient} (ADR 0013, D25): every Epic call is
 * logged, measured and traced here, whichever call site made it.
 *
 * <ul>
 *   <li><b>Log.</b> "Epic outbound call started" and "Epic outbound call completed" at
 *       {@code INFO}, with the {@code call} — {@code discovery}, {@code jwks} or {@code token},
 *       as its call site named it — the method, where it went with no query, the status and
 *       {@code event.duration_ms}. A call that got no answer — a timeout, no connection — is
 *       "Epic outbound call failed" at {@code ERROR}, with its {@code network} category and the
 *       failure's stack under its type name alone. Neither a body nor a header is ever read.
 *   <li><b>Metrics.</b> The {@code epic.outbound} timer, and the {@code epic.outbound.errors}
 *       counter of calls that got no answer or a {@code 5xx} — Epic being unavailable — both
 *       tagged {@code call}. A counter of its own name, because Prometheus allows one type per
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
            LogEvent.outboundFailed(log, call, method, url,
                            TimeUnit.NANOSECONDS.toMillis(elapsed))
                    .setCause(RedactedFaultException.of(noAnswer, noAnswer.getClass().getName()))
                    .log();
            throw noAnswer;
        }
        long elapsed = System.nanoTime() - started;
        int status = response.getStatusCode().value();
        measure(call, elapsed, status >= 500);
        LogEvent.outboundEnd(log, call, method, url, status,
                        TimeUnit.NANOSECONDS.toMillis(elapsed))
                .log();
        return response;
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
