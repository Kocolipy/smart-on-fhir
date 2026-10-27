package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ShippedConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The service learns where its database is from {@code DATABASE_URL} alone (ADR 0013; #68).
 *
 * <p>There is no fallback URL. A fallback is a published default for a deployment that forgot
 * the setting, and it would point the service at whatever answers on that
 * address, so there is none: a local run reads it from {@code backend/.env}, the tests set it
 * themselves, and the deployed stack sets it in UserData. That a start with the placeholder
 * unresolved fails naming the variable is {@link DataSourceConfigTests}'.
 *
 * <p>Read through {@link ShippedConfiguration}, because the test resources'
 * {@code application.yaml} shadows the main one on the classpath.
 */
class DatabaseUrlTests {

    @Test
    void theServicesDatabaseIsDatabaseUrlWithNoFallback() {
        assertThat(ShippedConfiguration.under().bind("spring.datasource.url", String.class).get())
                .isEqualTo("${DATABASE_URL}");
    }
}
