package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The migrate step, run as a deployment runs it — its own entry point, on an empty database the
 * owner login owns — and read back as the owner login (ADR 0015, ac-1 and as-2; #66).
 *
 * <p>The settings arrive as command-line arguments, which the deployed {@code migrate} profile
 * would otherwise supply from the environment: the test resources' {@code application.yaml}
 * shadows the main one, and {@code MigrationProfilesTests} holds what that profile sets.
 */
class MigrationStepIntegrationTests {

    private static final String SCHEMA_HISTORY =
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank";

    private static final String PUBLIC_TABLES =
            "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'";

    private static String[] migrate(String url, String appLogin, String retentionLogin) {
        return new String[] {
            "--spring.flyway.enabled=true",
            "--spring.flyway.url=" + url,
            "--spring.flyway.user=" + ContainerTestConfiguration.OWNER_LOGIN,
            "--spring.flyway.password=" + ContainerTestConfiguration.OWNER_PASSWORD,
            "--spring.flyway.placeholders[app_login]=" + appLogin,
            "--spring.flyway.placeholders[retention_login]=" + retentionLogin,
        };
    }

    private static String[] migrate(String url) {
        return migrate(url, ContainerTestConfiguration.APP_LOGIN,
                ContainerTestConfiguration.RETENTION_LOGIN);
    }

    private static JdbcTemplate asOwner(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url,
                ContainerTestConfiguration.OWNER_LOGIN, ContainerTestConfiguration.OWNER_PASSWORD));
    }

    private static String emptyDatabase() throws SQLException {
        return ContainerTestConfiguration.emptyDatabase();
    }

    /**
     * The entry point returns once every migration is applied, so the process exits with status
     * zero.
     */
    @Test
    void theStepAppliesEveryMigrationAndReturns() throws SQLException {
        String url = emptyDatabase();

        MigrationStep.main(migrate(url));

        assertThat(asOwner(url).queryForList(SCHEMA_HISTORY, String.class))
                .containsExactly("1", "2", "3", "4", "5", "6");
    }

    /** The step leaves nothing running once it has migrated: the context it started is closed. */
    @Test
    void theStepClosesWhatItStarted() throws SQLException {
        ConfigurableApplicationContext step = MigrationStep.migrate(migrate(emptyDatabase()));

        assertThat(step.isActive()).isFalse();
    }

    /** Nothing of the service starts: no web server, and no pool for a request to run on. */
    @Test
    void theStepStartsOnlyFlyway() throws SQLException {
        try (ConfigurableApplicationContext step = MigrationStep.start(migrate(emptyDatabase()))) {

            assertThat(List.of(step instanceof WebServerApplicationContext,
                            step.getBeanNamesForType(DataSource.class).length))
                    .containsExactly(false, 0);
        }
    }

    /** The step runs in the profile that alone turns migrations on for a deployment. */
    @Test
    void theStepRunsInTheMigrateProfile() throws SQLException {
        try (ConfigurableApplicationContext step = MigrationStep.start(migrate(emptyDatabase()))) {

            assertThat(step.getEnvironment().getActiveProfiles()).containsExactly("migrate");
        }
    }

    /**
     * A refused name ends the entry point with the refusal, so the process exits non-zero, before
     * Flyway has created even its history table.
     */
    @Test
    void aLoginNameOutsideThePatternFailsBeforeAnyDdlRuns() throws SQLException {
        String url = emptyDatabase();

        assertThatThrownBy(() -> MigrationStep.main(
                        migrate(url, "app\"; DROP TABLE scim_users; --", "backend_retention_login")))
                .hasRootCauseMessage("Flyway placeholder app_login (DATABASE_USERNAME) must be a"
                        + " login name matching ^[a-z][a-z0-9_]{2,30}$");

        assertThat(asOwner(url).queryForObject(PUBLIC_TABLES, Integer.class)).isZero();
    }

    /**
     * The owner's connection is opened with the driver properties every pool's is: here one that
     * demands TLS of a server that offers none, so the step fails before any migration runs rather
     * than migrating over a connection the deployment said must be encrypted.
     */
    @Test
    void theOwnersConnectionIsOpenedWithTheServicesDriverProperties() throws SQLException {
        String url = emptyDatabase();
        String[] args = Stream.concat(Stream.of(migrate(url)),
                        Stream.of("--spring.datasource.hikari.data-source-properties.sslmode=require"))
                .toArray(String[]::new);

        assertThatThrownBy(() -> MigrationStep.main(args))
                .hasRootCauseMessage("The server does not support SSL.");

        assertThat(asOwner(url).queryForObject(PUBLIC_TABLES, Integer.class)).isZero();
    }

    /**
     * A migration that fails ends the entry point with the failure, so the process exits non-zero
     * and a deployment that runs it first never starts the service: here {@code V3} names a login
     * that does not exist, so neither it nor any migration after it is applied.
     */
    @Test
    void aFailingMigrationFailsTheStep() throws SQLException {
        String url = emptyDatabase();

        assertThatThrownBy(() -> MigrationStep.main(
                        migrate(url, "no_such_login", "backend_retention_login")))
                .hasRootCauseMessage("ERROR: role \"no_such_login\" does not exist");

        assertThat(asOwner(url).queryForList(SCHEMA_HISTORY, String.class))
                .containsExactly("1", "2");
    }
}
