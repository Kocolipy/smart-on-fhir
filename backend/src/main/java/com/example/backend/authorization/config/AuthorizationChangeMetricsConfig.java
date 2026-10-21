package com.example.backend.authorization.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The {@code authorization.changes} counter, always registered. */
@Configuration
public class AuthorizationChangeMetricsConfig {

    @Bean
    public AuthorizationChangeMetrics authorizationChangeMetrics(MeterRegistry registry) {
        return new AuthorizationChangeMetrics(registry);
    }
}
