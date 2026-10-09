package com.example.backend.auth.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code login} counter, always registered: password Login exists whether or not Epic Login is
 * on, and both methods' endings are counted on the one meter.
 */
@Configuration
public class LoginMetricsConfig {

    @Bean
    public LoginMetrics loginMetrics(MeterRegistry registry) {
        return new LoginMetrics(registry);
    }
}
