package com.example.backend.auth.epic.config;

import com.example.backend.auth.domain.PendingAuthorizations;
import com.example.backend.auth.epic.ClientAssertionSigner;
import com.example.backend.auth.epic.EpicJwkSource;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicProviderMetadata;
import com.example.backend.auth.epic.EpicRetryPause;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.client.RestClient;

/**
 * Epic Login on the application chain (ADR 0013, flow steps 2–4): the one {@link EpicLogin} the
 * chain applies, which is where whether Epic Login is on is decided, and what the on adapter is
 * built from — discovery and Epic's keys, each fetched on first use and kept (D26), so startup
 * never contacts Epic.
 *
 * <p>With the switch off the adapter is {@link EpicLoginOff}: there is no flow, no discovery and
 * no outbound client — the release gate answers {@code 404} for every Epic route — and the chain
 * is otherwise exactly the password-only one.
 */
@Configuration
public class EpicSecurityConfig {

    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicProviderMetadata epicProviderMetadata(
            @Qualifier(EpicRestClientConfig.EPIC_REST_CLIENT) RestClient epicRestClient,
            EpicLoginSettings settings,
            Clock clock) {
        return new EpicProviderMetadata(epicRestClient, settings.oauthIssuer(), clock);
    }

    /** The wait before each of D26's JWKS refetches. */
    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicRetryPause epicRetryPause() {
        return EpicRetryPause.SLEEP;
    }

    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicJwkSource epicJwkSource(
            @Qualifier(EpicRestClientConfig.EPIC_REST_CLIENT) RestClient epicRestClient,
            EpicProviderMetadata metadata,
            Clock clock,
            EpicRetryPause pause) {
        return new EpicJwkSource(epicRestClient, metadata, clock, pause);
    }

    /**
     * Epic Login on: the whole flow. Its handlers are Epic Login's web adapter, found by the
     * framework type the OAuth 2.0 login filter takes, so this configuration need not depend on
     * the web adapter; each type has the one bean, which exists only while Epic Login is on.
     */
    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicLogin epicLoginOn(
            EpicLoginSettings settings,
            EpicProviderMetadata metadata,
            @Qualifier(EpicRestClientConfig.EPIC_REST_CLIENT) RestClient epicRestClient,
            EpicJwkSource epicKeys,
            ClientAssertionSigner signer,
            Clock clock,
            AuthenticationSuccessHandler signIn,
            AuthenticationFailureHandler signInFailure,
            PendingAuthorizations pendingAuthorizations) {
        EpicLoginFlow flow = new EpicLoginFlow(settings,
                new EpicClientRegistrations(settings, metadata), epicRestClient, epicKeys, signer,
                clock, signIn, signInFailure, pendingAuthorizations);
        return new EpicLoginOn(settings.signingKeys(), flow::applyTo);
    }

    /** Epic Login off: the release gate alone. */
    @Bean
    @Conditional(EpicLogin.WhenOff.class)
    public EpicLogin epicLoginOff() {
        return new EpicLoginOff();
    }

    /**
     * Always present, so the failure handler can record an Epic Login's failed call whether or not
     * the flow is. The Login's ending itself is counted by {@code login}, which is the password
     * Login's too, and so is registered outside Epic's configuration
     * ({@code com.example.backend.auth.config.LoginMetricsConfig}).
     */
    @Bean
    public EpicCallMetrics epicCallMetrics(MeterRegistry registry) {
        return new EpicCallMetrics(registry);
    }
}
