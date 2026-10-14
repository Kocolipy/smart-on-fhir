package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Which processes migrate, and which read the owner credential, as the deployed configuration
 * decides it: Flyway is on, as the owner login, in the {@code local}, {@code test} and
 * {@code migrate} profiles only, so the service a deployment runs neither migrates nor reads an
 * owner credential (ADR 0015, ac-1; #66).
 *
 * <p>Read from {@code src/main/resources/application.yaml} on disk, with the documents it imports,
 * through Boot's own config-data processing: the test resources' {@code application.yaml} shadows
 * the main one on the classpath, so the classpath would describe the test contexts instead. No
 * system property or environment variable takes part, so a developer's shell cannot change the
 * answer, and a variable a setting reads stays visible as its unresolved placeholder.
 */
class MigrationProfilesTests {

    private static final String DEPLOYED_CONFIGURATION = "file:src/main/resources/application.yaml";

    /** The deployed configuration with {@code profiles} active, and nothing else. */
    private static Binder deployedConfigurationUnder(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "location", Map.of("spring.config.location", DEPLOYED_CONFIGURATION)));
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);
        return Binder.get(environment);
    }

    private static boolean migratesUnder(String... profiles) {
        return deployedConfigurationUnder(profiles)
                .bind("spring.flyway.enabled", Boolean.class)
                .orElse(true);
    }

    @Test
    void theDeployedServiceDoesNotMigrate() {
        assertThat(migratesUnder()).isFalse();
    }

    @Test
    void theDevProfileDoesNotMigrate() {
        assertThat(migratesUnder("dev")).isFalse();
    }

    @Test
    void anyOtherProfileDoesNotMigrate() {
        assertThat(migratesUnder("production")).isFalse();
    }

    @Test
    void theMigrateStepMigrates() {
        assertThat(migratesUnder("migrate")).isTrue();
    }

    @Test
    void aLocalRunMigrates() {
        assertThat(migratesUnder("local")).isTrue();
    }

    /** The Epic E2E run's profiles: local for the schema, dev for the launcher's http URLs. */
    @Test
    void aLocalRunInTheDevProfileMigrates() {
        assertThat(migratesUnder("local", "dev")).isTrue();
    }

    @Test
    void theTestProfileMigrates() {
        assertThat(migratesUnder("test")).isTrue();
    }

    /** No setting of the deployed service names the owner login or its password. */
    @Test
    void theDeployedServiceReadsNoOwnerCredential() {
        Binder deployed = deployedConfigurationUnder();
        assertThat(List.of(
                        deployed.bind("spring.flyway.user", String.class).isBound(),
                        deployed.bind("spring.flyway.password", String.class).isBound()))
                .containsExactly(false, false);
    }

    @Test
    void theMigrateStepConnectsAsTheOwnerLogin() {
        Binder migrate = deployedConfigurationUnder("migrate");
        assertThat(List.of(
                        migrate.bind("spring.flyway.user", String.class).get(),
                        migrate.bind("spring.flyway.password", String.class).get()))
                .containsExactly("${DATABASE_OWNER_USERNAME}", "${DATABASE_OWNER_PASSWORD}");
    }

    /** It reaches the service's own database, which it has no pool to borrow a URL from. */
    @Test
    void theMigrateStepReachesTheServicesDatabase() {
        assertThat(deployedConfigurationUnder("migrate")
                        .bind("spring.flyway.url", String.class).get())
                .isEqualTo("${DATABASE_URL}");
    }

    /**
     * The migrations learn the app and retention logins' names, under the placeholder names they
     * use, and no other value — a password least of all.
     */
    @Test
    void theMigrationsLearnTheOtherLoginsNamesOnly() {
        assertThat(deployedConfigurationUnder("migrate")
                        .bind("spring.flyway.placeholders",
                                Bindable.mapOf(String.class, String.class))
                        .get())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "app_login", "${DATABASE_USERNAME}",
                        "retention_login", "${DATABASE_RETENTION_USERNAME}"));
    }
}
