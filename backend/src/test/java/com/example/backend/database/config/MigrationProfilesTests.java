package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ShippedConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Which processes migrate, and which read the owner credential, as the deployed configuration
 * decides it: Flyway is on, as the owner login, in the {@code local}, {@code test} and
 * {@code migrate} profiles only, so the service a deployment runs neither migrates nor reads an
 * owner credential (ADR 0013, ac-1; #66).
 *
 * <p>Read through {@link ShippedConfiguration}, so no shell setting changes the answer and a
 * variable a setting reads stays visible as its unresolved placeholder.
 */
class MigrationProfilesTests {

    /** The deployed configuration with {@code profiles} active, and nothing else. */
    private static Binder deployedConfigurationUnder(String... profiles) {
        return ShippedConfiguration.under(profiles);
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
