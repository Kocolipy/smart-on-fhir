package com.example.backend.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.database.config.DatabaseConnections;
import com.example.backend.database.config.DatabaseConnectionsFixture;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The retention pool: the audit retention job's own small pool, the connections module's pool for
 * the retention login, which it binds from {@code app.datasource.retention.*}. Built without
 * opening a connection: the pool starts on its first use.
 */
class AuditRetentionPoolConfigTests {

    private final AuditRetentionPoolConfig config = new AuditRetentionPoolConfig();

    /** The service's connections: its database, as the app login. */
    private static final DatabaseConnections CONNECTIONS = DatabaseConnectionsFixture.connections();

    private static final AuditRetentionLoginProperties RETENTION_LOGIN =
            new AuditRetentionLoginProperties("backend_retention_login", "the-retention-logins");

    private HikariDataSource retentionPool() {
        return config.auditRetentionDataSource(CONNECTIONS, RETENTION_LOGIN);
    }

    @Test
    void thePoolReachesTheMainPoolsDatabaseThroughTheSameDriver() {
        try (HikariDataSource pool = retentionPool()) {

            assertThat(List.of(pool.getJdbcUrl(), pool.getDriverClassName()))
                    .containsExactly("jdbc:postgresql://db.internal:5432/backend",
                            "org.postgresql.Driver");
        }
    }

    /** Never the app login: the retention pool connects as the login it was given. */
    @Test
    void thePoolConnectsAsTheRetentionLogin() {
        try (HikariDataSource pool = retentionPool()) {

            assertThat(List.of(pool.getUsername(), pool.getPassword()))
                    .containsExactly("backend_retention_login", "the-retention-logins");
        }
    }

    /** Small, named and with the bounded 10 s connection timeout, never the main pool's sizing. */
    @Test
    void thePoolIsNamedRetentionHoldsTwoConnectionsAndWaitsTenSeconds() {
        try (HikariDataSource pool = retentionPool()) {

            assertThat(List.<Object>of(pool.getPoolName(), pool.getMaximumPoolSize(),
                            pool.getConnectionTimeout()))
                    .containsExactly("retention", 2, 10_000L);
        }
    }

    @Test
    void theTemplateRunsOnTheRetentionPool() {
        try (HikariDataSource pool = retentionPool()) {

            assertThat(config.auditRetentionJdbcTemplate(pool).getDataSource()).isSameAs(pool);
        }
    }

    @Test
    void theTransactionManagerRunsOnTheRetentionPool() {
        try (HikariDataSource pool = retentionPool()) {

            assertThat(config.auditRetentionTransactionManager(pool).getDataSource()).isSameAs(pool);
        }
    }

    /** The login's password never reaches a log line through the bound settings' rendering. */
    @Test
    void theBoundLoginNeverRendersItsPassword() {
        assertThat(RETENTION_LOGIN.toString())
                .isEqualTo("DatabaseLogin[username=backend_retention_login, password=<redacted>]");
    }

    private final ApplicationContextRunner contexts = DatabaseConnectionsFixture.mainPoolContexts()
            .withUserConfiguration(AuditRetentionPoolConfig.class);

    /** The retention login reaches the pool from its own prefix. */
    @Test
    void theRetentionLoginBindsFromItsOwnPrefix() {
        contexts.withPropertyValues(DatabaseConnectionsFixture.retentionLoginSettings())
                .run(context -> {
                    HikariDataSource pool = context.getBean(
                            "auditRetentionDataSource", HikariDataSource.class);
                    assertThat(List.of(pool.getUsername(), pool.getPassword()))
                            .containsExactly("backend_retention_login", "the-retention-logins");
                });
    }

    /**
     * With no retention login configured the pool has none: nothing falls back to the app login,
     * and the server refuses the pool's first connection.
     */
    @Test
    void anUnsetRetentionLoginTakesNothingFromTheAppLogin() {
        contexts.run(context -> {
            HikariDataSource pool = context.getBean(
                    "auditRetentionDataSource", HikariDataSource.class);
            assertThat(List.of(String.valueOf(pool.getUsername()),
                            String.valueOf(pool.getPassword())))
                    .containsExactly("null", "null");
        });
    }
}
