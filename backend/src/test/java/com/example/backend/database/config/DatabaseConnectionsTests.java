package com.example.backend.database.config;

import static com.example.backend.database.config.DatabaseConnectionsFixture.driverProperties;
import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The database connections module: a pool for a login, told only its name, its login, its size
 * and its connection timeout, reaches the database the module knows. Built without opening a
 * connection: a pool starts on its first use.
 */
class DatabaseConnectionsTests {

    private static final DatabaseConnections.DatabaseLogin RETENTION =
            DatabaseConnectionsFixture.RETENTION_LOGIN;

    private static final DatabaseConnections CONNECTIONS = DatabaseConnectionsFixture.connections(
            Map.of("sslmode", "verify-full", "loginTimeout", "15"));

    @Test
    void aPoolReachesTheModulesDatabaseThroughItsDriver() {
        try (HikariDataSource pool = CONNECTIONS.pool("retention", RETENTION, 2, Duration.ofSeconds(10))) {

            assertThat(List.of(pool.getJdbcUrl(), pool.getDriverClassName()))
                    .containsExactly("jdbc:postgresql://db.internal:5432/backend",
                            "org.postgresql.Driver");
        }
    }

    @Test
    void aPoolOpensEveryConnectionWithTheModulesDriverProperties() {
        try (HikariDataSource pool = CONNECTIONS.pool("retention", RETENTION, 2, Duration.ofSeconds(10))) {

            assertThat(pool.getDataSourceProperties())
                    .isEqualTo(driverProperties(Map.of("sslmode", "verify-full", "loginTimeout", "15")));
        }
    }

    @Test
    void aPoolConnectsAsTheLoginItWasAskedFor() {
        try (HikariDataSource pool = CONNECTIONS.pool("retention", RETENTION, 2, Duration.ofSeconds(10))) {

            assertThat(List.of(pool.getUsername(), pool.getPassword()))
                    .containsExactly("backend_retention_login", "the-retention-logins");
        }
    }

    @Test
    void aPoolIsNamedSizedAndTimedAsAsked() {
        try (HikariDataSource pool = CONNECTIONS.pool("retention", RETENTION, 3, Duration.ofMillis(750))) {

            assertThat(List.<Object>of(pool.getPoolName(), pool.getMaximumPoolSize(),
                            pool.getConnectionTimeout()))
                    .containsExactly("retention", 3, 750L);
        }
    }

    /**
     * Nothing runs on a new connection, and each statement is its own transaction until a
     * transaction manager says otherwise: the login's privileges are the pool's.
     */
    @Test
    void aPoolRunsNothingOnANewConnectionAndAutoCommits() {
        try (HikariDataSource pool = CONNECTIONS.pool("retention", RETENTION, 2, Duration.ofSeconds(10))) {

            assertThat(List.of(String.valueOf(pool.getConnectionInitSql()), pool.isAutoCommit()))
                    .containsExactly("null", true);
        }
    }

    /** The main pool's form: its size is the {@code spring.datasource.hikari.*} binding's to set. */
    @Test
    void anUnsizedPoolLeavesItsSizeToItsOwnSettings() {
        try (HikariDataSource pool = CONNECTIONS.pool("app", RETENTION, Duration.ofSeconds(10));
                HikariDataSource unconfigured = new HikariDataSource()) {

            assertThat(pool.getMaximumPoolSize()).isEqualTo(unconfigured.getMaximumPoolSize());
        }
    }

    @Test
    void anUnsizedPoolIsStillNamedTimedAndReachesTheDatabaseAsTheLogin() {
        try (HikariDataSource pool = CONNECTIONS.pool("app", RETENTION, Duration.ofSeconds(10))) {

            assertThat(List.<Object>of(pool.getPoolName(), pool.getConnectionTimeout(),
                            pool.getJdbcUrl(), pool.getUsername(), pool.getDataSourceProperties()))
                    .containsExactly("app", 10_000L, "jdbc:postgresql://db.internal:5432/backend",
                            "backend_retention_login",
                            driverProperties(Map.of("sslmode", "verify-full", "loginTimeout", "15")));
        }
    }

    /** One pool's driver properties are its own: a change to them reaches no other pool. */
    @Test
    void aChangeToOnePoolsPropertiesReachesNoOtherPool() {
        try (HikariDataSource first = CONNECTIONS.pool("app", RETENTION, Duration.ofSeconds(10))) {
            first.getDataSourceProperties().setProperty("sslmode", "disable");

            try (HikariDataSource second =
                    CONNECTIONS.pool("retention", RETENTION, 2, Duration.ofSeconds(10))) {
                assertThat(second.getDataSourceProperties().getProperty("sslmode"))
                        .isEqualTo("verify-full");
            }
        }
    }

    @Test
    void theAppLoginIsTheOneTheModuleWasGiven() {
        assertThat(CONNECTIONS.appLogin())
                .isEqualTo(new DatabaseConnections.DatabaseLogin("backend_app_login", "the-app-logins"));
    }

    /** A login is safe to log: its password never reaches its rendering. */
    @Test
    void aLoginNeverRendersItsPassword() {
        assertThat(RETENTION.toString())
                .isEqualTo("DatabaseLogin[username=backend_retention_login, password=<redacted>]");
    }

    /** With none configured, a connection is opened with no driver property at all. */
    @Test
    void unsetDriverPropertiesAreNone() {
        assertThat(new DatabaseConnectionProperties(null).asDriverProperties()).isEmpty();
    }
}
