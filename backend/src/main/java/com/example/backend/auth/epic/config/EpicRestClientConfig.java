package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicOutboundInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.net.http.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@code epicRestClient}, the one outbound client every Epic call goes through (D25): discovery,
 * the JWKS fetch and the token call share its timeouts — {@code APP_EPIC_CONNECT_TIMEOUT} and
 * {@code APP_EPIC_READ_TIMEOUT}, 2 and 5 seconds by default — its logging and metrics, and its
 * tracing.
 *
 * <p>Built from Spring Boot's auto-configured {@link RestClient.Builder}, so each call is
 * observed as every client of this service's is ({@code http.client.requests}, a client span in
 * the request's trace), with {@link EpicOutboundInterceptor} on top for the Epic log, the
 * {@code epic.outbound} meters and the outbound {@code traceparent}. There is no circuit breaker:
 * login volume is low, and the edge throttle bounds the load (D19).
 *
 * <p>It follows no redirect: every Epic endpoint is the one discovery named, and a redirect away
 * from it is not an answer. Certificate and hostname checks are the JDK client's defaults and are
 * never disabled (D21). Exists only while Epic Login is on.
 */
@Configuration
public class EpicRestClientConfig {

    /** The bean name every Epic call site injects the client by. */
    public static final String EPIC_REST_CLIENT = "epicRestClient";

    @Bean(EPIC_REST_CLIENT)
    @Conditional(EpicLoginEnabled.class)
    public RestClient epicRestClient(RestClient.Builder builder, EpicLoginSettings settings,
            MeterRegistry registry, ObjectProvider<Tracer> tracer) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(settings.readTimeout());
        return builder
                .requestFactory(requests)
                .requestInterceptor(new EpicOutboundInterceptor(
                        registry, tracer.getIfAvailable(() -> Tracer.NOOP)))
                .build();
    }
}
