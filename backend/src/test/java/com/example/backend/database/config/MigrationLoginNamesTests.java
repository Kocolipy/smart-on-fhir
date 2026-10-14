package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
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

    @Test
    void aNameOfThreeCharactersIsAccepted() {
        FluentConfiguration configuration = withLogins("app", "ret");

        check.customize(configuration);

        assertThat(configuration.getPlaceholders()).containsEntry("app_login", "app");
    }

    @Test
    void aNameOfThirtyOneCharactersIsAccepted() {
        String longest = "a" + "b".repeat(30);
        FluentConfiguration configuration = withLogins(longest, "retention");

        check.customize(configuration);

        assertThat(configuration.getPlaceholders()).containsEntry("app_login", longest);
    }

    @Test
    void aNameOfTwoCharactersIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("ab", "retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Flyway placeholder app_login (DATABASE_USERNAME) must be a login"
                        + " name matching ^[a-z][a-z0-9_]{2,30}$");
    }

    @Test
    void aNameOfThirtyTwoCharactersIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("a" + "b".repeat(31), "retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Flyway placeholder app_login ");
    }

    /** A quote or a space is how a name would carry SQL out of its identifier. */
    @Test
    void aNameCarryingSqlIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("app\"; DROP TABLE x; --", "ret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Flyway placeholder app_login ");
    }

    @Test
    void aNameWithAnUppercaseLetterIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("Backend_app", "retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Flyway placeholder app_login ");
    }

    @Test
    void aNameStartingWithADigitIsRefused() {
        assertThatThrownBy(() -> check.customize(withLogins("1backend", "retention")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Flyway placeholder app_login ");
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
