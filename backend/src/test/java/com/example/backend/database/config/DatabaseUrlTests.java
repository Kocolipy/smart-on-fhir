package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The service learns where its database is from {@code DATABASE_URL} alone (ADR 0013; #68).
 *
 * <p>There is no fallback URL. A fallback is a published default for a deployment that forgot
 * the setting, and it would point the service at whatever answers on that
 * address, so there is none: a local run reads it from {@code backend/.env}, the tests set it
 * themselves, and the deployed stack sets it in UserData. That a start with the placeholder
 * unresolved fails naming the variable is {@link DataSourceConfigTests}'.
 *
 * <p>Read from {@code src/main/resources/application.yaml} on disk, as
 * {@link MigrationProfilesTests} reads it, because the test resources' {@code application.yaml}
 * shadows it on the classpath.
 */
class DatabaseUrlTests {

    private static final String DEPLOYED_CONFIGURATION = "file:src/main/resources/application.yaml";

    @Test
    void theServicesDatabaseIsDatabaseUrlWithNoFallback() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "location", Map.of("spring.config.location", DEPLOYED_CONFIGURATION)));
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), new DefaultBootstrapContext());

        assertThat(Binder.get(environment).bind("spring.datasource.url", String.class).get())
                .isEqualTo("${DATABASE_URL}");
    }
}
