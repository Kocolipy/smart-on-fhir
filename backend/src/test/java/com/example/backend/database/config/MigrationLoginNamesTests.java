package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The login names the migrations splice into DDL are checked before Flyway runs: each matches
 * {@code ^[a-z][a-z0-9_]{2,30}$}, and the two differ. Built on a configuration with no data
 * source, so nothing here could reach a database.
 */
class MigrationLoginNamesTests {

    private final MigrationLoginNames check = new MigrationLoginNames();

    private static FluentConfiguration withLogins(String app, String retention) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("app_login", app);
        placeholders.put("retention_login", retention);
        return Flyway.configure().placeholders(placeholders);
    }

    @Test
    void theDeploymentsDefaultNamesAreAccepted() {
        FluentConfiguration configuration =
                withLogins("backend_app_login", "backend_retention_login");

        check.customize(configuration);

        assertThat(configuration.getPlaceholders())
                .containsEntry("app_login", "backend_app_login")
                .containsEntry("retention_login", "backend_retention_login");
    }

    /** The pattern's length bounds, three to thirty-one characters, are themselves accepted. */
    @ParameterizedTest(name = "a name of {0} characters is accepted")
    @ValueSource(ints = {3, 31})
    void aNameAtTheLengthBoundsIsAccepted(int length) {
        String name = "a" + "b".repeat(length - 1);
        FluentConfiguration configuration = withLogins(name, "retention");

        check.customize(configuration);

        assertThat(configuration.getPlaceholders()).containsEntry("app_login", name);
    }

    /**
     * A name one character outside either length bound, an uppercase letter or a leading digit is
     * refused — and a quote or a space, which is how a name would carry SQL out of its identifier.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ab", "abbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "app\"; DROP TABLE x; --",
            "Backend_app", "1backend"})
    void aNameOutsideThePatternIsRefused(String name) {
        assertThatThrownBy(() -> check.customize(withLogins(name, "retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Flyway placeholder app_login (DATABASE_USERNAME) must be a login"
                        + " name matching ^[a-z][a-z0-9_]{2,30}$");
    }

    @Test
    void anUnsetAppLoginIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins(null, "retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Flyway placeholder app_login ");
    }

    /** The retention login is held to the same rule, and named by its own variable. */
    @Test
    void anInvalidRetentionLoginIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("backend_app_login", "Retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Flyway placeholder retention_login (DATABASE_RETENTION_USERNAME) must"
                        + " be a login name matching ^[a-z][a-z0-9_]{2,30}$");
    }

    /** One login holding both roles would let the request pool delete audit events. */
    @Test
    void theSameLoginForAppAndRetentionIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("backend_login", "backend_login")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Flyway placeholders app_login and retention_login name the same"
                        + " login; the app and retention logins must differ");
    }

    /**
     * The configuration the migrate step imports and the service scans gives Flyway the check,
     * so Boot applies it as it builds the migration.
     */
    @Test
    void theMigrationConfigurationGivesFlywayTheCheck() {
        new ApplicationContextRunner().withUserConfiguration(MigrationConfig.class)
                .run(context -> assertThatThrownBy(() -> context
                        .getBean(MigrationLoginNames.class)
                        .customize(withLogins("ab", "retention")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("Flyway placeholder app_login "));
    }
}
