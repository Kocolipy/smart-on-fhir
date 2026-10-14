package com.example.backend.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The retention pool: the audit retention job's own small pool, on the main pool's database but
 * connected as the retention login, which it binds from {@code app.datasource.retention.*}.
 * Built without opening a connection: the pool starts on its first use.
 */
class AuditRetentionPoolConfigTests {

    private final AuditRetentionPoolConfig config = new AuditRetentionPoolConfig();

    /** A main pool as the service declares one, connected as the app login. */
    private static HikariDataSource mainPool() {
        HikariDataSource main = new HikariDataSource();
        main.setJdbcUrl("jdbc:postgresql://db.internal:5432/backend");
        main.setUsername("backend_app_login");
        main.setPassword("the-app-logins");
        main.setDriverClassName("org.postgresql.Driver");
        main.setMaximumPoolSize(10);
        return main;
    }

    @Test
    void thePoolReachesTheMainPoolsDatabaseThroughTheSameDriver() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditRetentionDataSource(main)) {

            assertThat(List.of(pool.getJdbcUrl(), pool.getDriverClassName()))
                    .containsExactly("jdbc:postgresql://db.internal:5432/backend",
                            "org.postgresql.Driver");
        }
    }

    /** Never the app login: the retention pool's credentials are its own, bound separately. */
    @Test
    void thePoolTakesNoCredentialFromTheMainPool() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditRetentionDataSource(main)) {

            assertThat(List.of(String.valueOf(pool.getUsername()), String.valueOf(pool.getPassword())))
                    .containsExactly("null", "null");
        }
    }

    /** Small, named and with the bounded 10 s connection timeout, never the main pool's sizing. */
    @Test
    void thePoolIsNamedRetentionHoldsTwoConnectionsAndWaitsTenSeconds() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditRetentionDataSource(main)) {

            assertThat(List.<Object>of(pool.getPoolName(), pool.getMaximumPoolSize(),
                            pool.getConnectionTimeout()))
                    .containsExactly("retention", 2, 10_000L);
        }
    }

    @Test
    void theTemplateRunsOnTheRetentionPool() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditRetentionDataSource(main)) {

            assertThat(config.auditRetentionJdbcTemplate(pool).getDataSource()).isSameAs(pool);
        }
    }

    @Test
    void theTransactionManagerRunsOnTheRetentionPool() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditRetentionDataSource(main)) {

            assertThat(config.auditRetentionTransactionManager(pool).getDataSource()).isSameAs(pool);
        }
    }

    /** The retention login reaches the pool from its own prefix. */
    @Test
    void theRetentionLoginBindsFromItsOwnPrefix() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(AuditRetentionPoolConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://db.internal:5432/backend",
                        "spring.datasource.username=backend_app_login",
                        "app.datasource.retention.username=backend_retention_login",
                        "app.datasource.retention.password=the-retention-logins")
                .run(context -> {
                    HikariDataSource pool = context.getBean(
                            "auditRetentionDataSource", HikariDataSource.class);
                    assertThat(List.of(pool.getUsername(), pool.getPassword()))
                            .containsExactly("backend_retention_login", "the-retention-logins");
                });
    }
}
