package com.example.backend.database.config;

import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

/**
 * Checks the login names the migrations put into DDL before Flyway runs a single statement (ADR
 * 0015, as-2; #66).
 *
 * <p>The migrations learn the app and retention logins' names as Flyway placeholders and grant
 * each its role. A placeholder is spliced into the SQL text, so a name is accepted only when it
 * matches {@value #LOGIN_NAME_PATTERN} — the pattern the deployment's CloudFormation parameters
 * and its bootstrap enforce too — and the migrations quote it as an identifier besides. The two
 * names must also differ: one login holding both roles would let the request pool delete audit
 * events.
 *
 * <p>It runs as Boot builds Flyway's configuration, after the properties are applied and before
 * the migration bean exists, so a refused name fails the migrate step (or a local or test start)
 * before any connection is used for DDL. The message names the placeholder and the variable it is
 * read from, never the value.
 */
public final class MigrationLoginNames implements FlywayConfigurationCustomizer {

    /** The app login's name, granted {@code backend_app}; read from {@code DATABASE_USERNAME}. */
    public static final String APP_LOGIN = "app_login";

    /**
     * The retention login's name, granted {@code backend_audit_retention}; read from
     * {@code DATABASE_RETENTION_USERNAME}.
     */
    public static final String RETENTION_LOGIN = "retention_login";

    static final String LOGIN_NAME_PATTERN = "^[a-z][a-z0-9_]{2,30}$";

    private static final Pattern LOGIN_NAME = Pattern.compile(LOGIN_NAME_PATTERN);

    @Override
    public void customize(FluentConfiguration configuration) {
        Map<String, String> placeholders = configuration.getPlaceholders();
        String app = requireLoginName(placeholders, APP_LOGIN, "DATABASE_USERNAME");
        String retention =
                requireLoginName(placeholders, RETENTION_LOGIN, "DATABASE_RETENTION_USERNAME");
        if (app.equals(retention)) {
            throw new IllegalStateException("Flyway placeholders " + APP_LOGIN + " and "
                    + RETENTION_LOGIN + " name the same login; the app and retention logins must"
                    + " differ");
        }
    }

    private static String requireLoginName(
            Map<String, String> placeholders, String placeholder, String variable) {
        String name = placeholders.get(placeholder);
        if (name == null || !LOGIN_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Flyway placeholder " + placeholder + " (" + variable
                    + ") must be a login name matching " + LOGIN_NAME_PATTERN);
        }
        return name;
    }
}
