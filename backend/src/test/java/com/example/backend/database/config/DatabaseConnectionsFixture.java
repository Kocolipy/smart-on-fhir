package com.example.backend.database.config;

import java.util.Map;
import java.util.Properties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The service's database as the pool tests see it: one URL, one driver, the app login and the
 * retention login. Every pool test builds on these, so a pool that drifts from the main pool's
 * database, driver or driver properties shows up against the same literals everywhere.
 *
 * <p>Nothing here opens a connection: the host does not exist, and a pool starts on its first use.
 */
public final class DatabaseConnectionsFixture {

    /** The database every pool reaches. */
    public static final String URL = "jdbc:sqlserver://db.internal:1433;databaseName=backend";

    /** The driver every pool reaches it through. */
    public static final String DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

    /** The app login, the main pool's and the audit append pool's. */
    public static final DatabaseConnections.DatabaseLogin APP_LOGIN =
            new DatabaseConnections.DatabaseLogin("backend_app_login", "the-app-logins");

    /** The retention login, the retention pool's alone. */
    public static final DatabaseConnections.DatabaseLogin RETENTION_LOGIN =
            new DatabaseConnections.DatabaseLogin("backend_retention_login", "the-retention-logins");

    private DatabaseConnectionsFixture() {
    }

    /** The service's connections with no driver property set, as every deployment has today. */
    public static DatabaseConnections connections() {
        return connections(Map.of());
    }

    /** The service's connections, opening every connection with {@code driverProperties}. */
    public static DatabaseConnections connections(Map<String, String> driverProperties) {
        return new DatabaseConnections(URL, DRIVER, APP_LOGIN,
                new DatabaseConnectionProperties(driverProperties));
    }

    /** {@code entries} as the driver properties a pool hands its driver. */
    public static Properties driverProperties(Map<String, String> entries) {
        Properties properties = new Properties();
        properties.putAll(entries);
        return properties;
    }

    /**
     * A context holding the main pool, on {@link #URL} as {@link #APP_LOGIN}. A test adds the pool
     * configurations it is about, and {@link #retentionLoginSettings()} when it wants the retention
     * login bound.
     */
    public static ApplicationContextRunner mainPoolContexts() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + URL,
                        "spring.datasource.username=" + APP_LOGIN.username(),
                        "spring.datasource.password=" + APP_LOGIN.password());
    }

    /** {@link #RETENTION_LOGIN}, as a deployment sets it under {@code app.datasource.retention}. */
    public static String[] retentionLoginSettings() {
        return new String[] {
            "app.datasource.retention.username=" + RETENTION_LOGIN.username(),
            "app.datasource.retention.password=" + RETENTION_LOGIN.password(),
        };
    }
}
