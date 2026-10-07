package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.ClientAssertionSigner;
import com.example.backend.auth.epic.EpicLoginMetrics;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicProviderMetadata;
import com.example.backend.auth.epic.EpicSignIn;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Epic Login on the application chain (spec section 6): the {@link EpicLoginFlow} the chain
 * applies while {@code APP_EPIC_ENABLED} is on, and what it is built from.
 *
 * <p>With the switch off there is no flow, no discovery and no outbound client — the release gate
 * answers {@code 404} for every Epic route — and the chain is exactly the password-only one.
 */
@Configuration
public class EpicSecurityConfig {

    @Bean
    @Conditional(EpicLoginEnabled.class)
    public EpicProviderMetadata epicProviderMetadata(
            @Qualifier(EpicRestClientConfig.EPIC_REST_CLIENT) RestClient epicRestClient,
            EpicLoginSettings settings) {
        return new EpicProviderMetadata(epicRestClient, settings.oauthIssuer());
    }

    @Bean
    @Conditional(EpicLoginEnabled.class)
    public EpicLoginFlow epicLoginFlow(
            EpicLoginSettings settings,
            EpicProviderMetadata metadata,
            @Qualifier(EpicRestClientConfig.EPIC_REST_CLIENT) RestClient epicRestClient,
            ClientAssertionSigner signer,
            Clock clock,
            EpicSignIn signIn) {
        return new EpicLoginFlow(settings, new EpicClientRegistrations(settings, metadata),
                epicRestClient, signer, clock, signIn);
    }

    /** Always present, so the success handler has it whether or not the flow is. */
    @Bean
    public EpicLoginMetrics epicLoginMetrics(MeterRegistry registry) {
        return new EpicLoginMetrics(registry);
    }
}
