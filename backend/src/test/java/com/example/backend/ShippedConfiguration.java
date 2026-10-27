package com.example.backend;

import java.util.Map;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The configuration the service ships, read for a test that holds a setting to it.
 *
 * <p>Read from {@code src/main/resources/application.yaml} on disk, with the documents it
 * imports, through Boot's own config-data processing: the test resources' {@code application.yaml}
 * shadows the main one on the classpath, so the classpath would describe the test contexts
 * instead. No system property or environment variable takes part, so a developer's shell cannot
 * change the answer, and a variable a setting reads stays visible as its unresolved placeholder.
 */
public final class ShippedConfiguration {

    private static final String LOCATION = "file:src/main/resources/application.yaml";

    private ShippedConfiguration() {
    }

    /** The shipped configuration with {@code profiles} active, and nothing else. */
    public static Binder under(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "location", Map.of("spring.config.location", LOCATION)));
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);
        return Binder.get(environment);
    }
}
