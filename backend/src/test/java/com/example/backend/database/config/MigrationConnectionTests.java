package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Flyway's owner connection — the plain driver-based one Boot builds it — is opened with the
 * driver properties every pool's connections are. Nothing here connects.
 */
class MigrationConnectionTests {

    private static final DatabaseConnectionProperties TLS =
            new DatabaseConnectionProperties(Map.of("sslmode", "verify-full", "loginTimeout", "15"));

    private static FluentConfiguration flywayOn(javax.sql.DataSource connection) {
        return Flyway.configure().dataSource(connection);
    }

    @Test
    void theOwnersConnectionIsOpenedWithEveryDriverProperty() {
        SimpleDriverDataSource owner = new SimpleDriverDataSource();

        new MigrationConnection(TLS).customize(flywayOn(owner));

        Properties expected = new Properties();
        expected.putAll(Map.of("sslmode", "verify-full", "loginTimeout", "15"));
        assertThat(owner.getConnectionProperties()).isEqualTo(expected);
    }

    /** Unset, Flyway's connection is left exactly as Boot built it. */
    @Test
    void noDriverPropertiesLeaveTheOwnersConnectionAlone() {
        SimpleDriverDataSource owner = new SimpleDriverDataSource();

        new MigrationConnection(new DatabaseConnectionProperties(null)).customize(flywayOn(owner));

        assertThat(owner.getConnectionProperties()).isNull();
    }

    /** With nothing to add, a connection of any kind is acceptable. */
    @Test
    void noDriverPropertiesAcceptAnyConnection() {
        try (HikariDataSource pool = new HikariDataSource()) {
            FluentConfiguration configuration = flywayOn(pool);

            new MigrationConnection(new DatabaseConnectionProperties(Map.of())).customize(configuration);

            assertThat(configuration.getDataSource()).isSameAs(pool);
        }
    }

    /**
     * The configuration the migrate step imports binds the properties from the key the main pool
     * reads them from, and gives them to Flyway.
     */
    @Test
    void theMigrationConfigurationGivesFlywayTheServicesDriverProperties() {
        new ApplicationContextRunner().withUserConfiguration(MigrationConfig.class)
                .withPropertyValues(
                        "spring.datasource.hikari.data-source-properties.[sslMode]=require")
                .run(context -> {
                    SimpleDriverDataSource owner = new SimpleDriverDataSource();
                    context.getBean(MigrationConnection.class).customize(flywayOn(owner));
                    assertThat(owner.getConnectionProperties())
                            .containsExactly(Map.entry("sslMode", "require"));
                });
    }

    /**
     * The deployed migrate step is given the trust-store password the way the service is: in its
     * environment, which {@code migrate.sh} exports from Secrets Manager. Its owner connection's
     * driver reads it as its {@code trustStorePassword}.
     */
    @Test
    void theMigrationStepsDriverReadsTheTrustStorePasswordFromTheEnvironment() {
        new ApplicationContextRunner().withUserConfiguration(MigrationConfig.class)
                .withInitializer(DatabaseConnectionsFixture.environment(Map.of(
                        "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD",
                        "the-trust-stores")))
                .run(context -> {
                    SimpleDriverDataSource owner = new SimpleDriverDataSource();
                    context.getBean(MigrationConnection.class).customize(flywayOn(owner));
                    assertThat(DatabaseConnectionsFixture.asTheDriverReads(
                                    owner.getConnectionProperties(), "trustStorePassword"))
                            .isEqualTo("the-trust-stores");
                });
    }

    /** Never a migration over a connection the properties could not reach. */
    @Test
    void aConnectionThePropertiesCannotReachFailsTheStart() {
        try (HikariDataSource pool = new HikariDataSource()) {

            assertThatThrownBy(() -> new MigrationConnection(TLS).customize(flywayOn(pool)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Flyway's connection is not a driver-based one of its own, so the"
                            + " driver properties every connection is opened with cannot reach it");
        }
    }
}
