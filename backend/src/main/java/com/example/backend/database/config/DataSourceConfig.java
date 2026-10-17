package com.example.backend.database.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The database connections module ({@link DatabaseConnections}), and the main pool it builds: the
 * connections every request, the scheduled-job lock and JPA run on, as the app login (ADR 0013,
 * "Runtime model"; #66).
 *
 * <p>The main pool is declared here rather than left to Boot because the service has more than
 * one pool — the audit append pool and the retention pool beside this one — and the main one must
 * be the pool by declaration, not by being the only {@code DataSource} Boot happened to see. It is
 * a Hikari pool connected as Boot connects its own: from {@code spring.datasource.*}
 * ({@code DATABASE_URL}, {@code DATABASE_USERNAME}, {@code DATABASE_PASSWORD}), with every
 * {@code spring.datasource.hikari.*} setting bound onto it, so a deployment's existing settings
 * keep reaching it. Boot's own pool backs off, and its JPA, transaction-manager,
 * {@code JdbcTemplate}, health and metrics configurations use this one.
 *
 * <p>How it reaches the database — URL, driver and the driver properties of
 * {@code spring.datasource.hikari.data-source-properties} — is the module's, as every other
 * pool's is; the {@code spring.datasource.hikari.*} binding then sizes it.
 *
 * <p>The app login is a member of {@code backend_app} only, and nothing runs on a new connection
 * to change that: there is no {@code connection-init-sql}. The pool's privileges are exactly the
 * login's, enforced by the server.
 *
 * <p>Two defaults are this class's, and a {@code spring.datasource.hikari.*} setting may still
 * override either: the pool's name, {@value #POOL_NAME}, in its metrics and its health entry
 * (lm-16), and a 10 s connection timeout (ADR 0013's bounded timeouts).
 */
@Configuration
@EnableConfigurationProperties(DatabaseConnectionProperties.class)
public class DataSourceConfig {

    /** The main pool's name in its metrics, its health entry and its log lines. */
    public static final String POOL_NAME = "app";

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How every pool reaches the database, read as Boot reads the main pool's settings.
     *
     * <p>The URL has no fallback ({@code DATABASE_URL}, ADR 0013), so a start without it is refused
     * here, by the variable's name: an unset variable reaches this point as its own unresolved
     * placeholder, which the driver lookup would otherwise report only as a URL that is not JDBC.
     * The message never quotes the value, which may carry a trust store's password.
     */
    @Bean
    public DatabaseConnections databaseConnections(
            DataSourceProperties properties, DatabaseConnectionProperties connectionProperties) {
        String url = properties.getUrl();
        if (url == null || url.isBlank() || url.startsWith("${")) {
            throw new IllegalStateException("The service's database URL is not set: set"
                    + " DATABASE_URL (spring.datasource.url); it has no default");
        }
        return new DatabaseConnections(
                properties.determineUrl(),
                properties.determineDriverClassName(),
                new DatabaseConnections.DatabaseLogin(
                        properties.determineUsername(), properties.determinePassword()),
                connectionProperties);
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DatabaseConnections connections) {
        return connections.pool(POOL_NAME, connections.appLogin(), CONNECTION_TIMEOUT);
    }
}
