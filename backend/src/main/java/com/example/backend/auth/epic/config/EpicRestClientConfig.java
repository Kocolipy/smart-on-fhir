package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLoginSettings;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@code epicRestClient}, the one outbound client every Epic call goes through (D25): discovery,
 * the JWKS fetch and the token call share its timeouts — {@code APP_EPIC_CONNECT_TIMEOUT} and
 * {@code APP_EPIC_READ_TIMEOUT}, 2 and 5 seconds by default.
 *
 * <p>It follows no redirect: every Epic endpoint is the one discovery named, and a redirect away
 * from it is not an answer. Certificate and hostname checks are the JDK client's defaults and are
 * never disabled (D21).
 *
 * <p>Exists only while Epic Login is on. The outbound logging interceptor, the
 * {@code epic.outbound} metrics and building on Spring Boot's auto-configured
 * {@code RestClient.Builder} for tracing are the outbound-resilience step's; this is the client
 * they attach to.
 */
@Configuration
public class EpicRestClientConfig {

    /** The bean name every Epic call site injects the client by. */
    public static final String EPIC_REST_CLIENT = "epicRestClient";

    @Bean(EPIC_REST_CLIENT)
    @Conditional(EpicLoginEnabled.class)
    public RestClient epicRestClient(EpicLoginSettings settings) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(settings.readTimeout());
        return RestClient.builder().requestFactory(requests).build();
    }
}
