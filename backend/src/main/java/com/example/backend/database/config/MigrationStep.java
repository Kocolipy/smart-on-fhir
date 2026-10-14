package com.example.backend.database.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * The migrate step: the service's own JAR, started at this class instead of the service's, applies
 * the schema migrations as the owner login and exits (ADR 0015, ac-1; #66).
 *
 * <pre>
 * java -Dloader.main=com.example.backend.database.config.MigrationStep \
 *     -cp backend.jar org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 *
 * <p>It is the only process that ever holds the owner credential. A deployment runs it before
 * the service starts and does not start the service when it fails; the service itself runs with
 * Flyway off and receives only the app and retention logins, while {@code ddl-auto: validate}
 * still fails its start on a schema that drifted.
 *
 * <p>Nothing of the service starts here. This class is not a configuration the service scans:
 * it imports Flyway's auto-configuration and the login-name check ({@link MigrationConfig}) and
 * nothing else, so there is no web server, no pool, no Redis, no seeding and no scheduled job —
 * only Flyway, on a connection of its own. The {@value #PROFILE} profile is what turns Flyway on
 * and gives it the database, the owner login and the placeholders ({@code application.yaml}).
 * Migrating happens while the context starts, so a failed migration, or a refused login name,
 * fails the start: {@link #main} then ends with the exception, and the process exits non-zero.
 * Otherwise {@link #main} closes the context and returns, and with nothing left running the
 * process exits with status zero.
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(MigrationConfig.class)
public final class MigrationStep {

    /** The profile the step runs in, which alone of the deployed profiles enables Flyway. */
    public static final String PROFILE = "migrate";

    private MigrationStep() {
    }

    /** Migrates, and returns once done; throws when a migration or a login name fails. */
    public static void main(String[] args) {
        migrate(args);
    }

    /** Migrates, closes the step's context, and hands it back closed. */
    static ConfigurableApplicationContext migrate(String... args) {
        ConfigurableApplicationContext step = start(args);
        step.close();
        return step;
    }

    /** Starts the step's context, which migrates while it starts; the caller closes it. */
    static ConfigurableApplicationContext start(String... args) {
        SpringApplication application = new SpringApplication(MigrationStep.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(PROFILE);
        return application.run(args);
    }
}
