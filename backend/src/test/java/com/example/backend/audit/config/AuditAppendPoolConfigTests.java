package com.example.backend.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The audit append pool connects exactly as the main pool does, and sizes itself by its own two
 * settings. Built without opening a connection: the pool starts on its first append.
 */
class AuditAppendPoolConfigTests {

    private final AuditAppendPoolConfig config = new AuditAppendPoolConfig();

    /** A main pool as Boot binds one: URL, login, password, driver and the runtime role switch. */
    private static HikariDataSource mainPool() {
        HikariDataSource main = new HikariDataSource();
        main.setJdbcUrl("jdbc:postgresql://db.internal:5432/backend");
        main.setUsername("backend");
        main.setPassword("from-the-environment");
        main.setDriverClassName("org.postgresql.Driver");
        main.setConnectionInitSql("SET ROLE backend_app");
        main.setMaximumPoolSize(10);
        main.setConnectionTimeout(30_000);
        return main;
    }

    @Test
    void thePoolConnectsAsTheMainPoolDoesUnderTheSameRole() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditAppendDataSource(
                        main, new AuditAppendPoolProperties(null, null))) {

            assertThat(List.of(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword(),
                            pool.getDriverClassName(), pool.getConnectionInitSql()))
                    .containsExactly("jdbc:postgresql://db.internal:5432/backend", "backend",
                            "from-the-environment", "org.postgresql.Driver",
                            "SET ROLE backend_app");
        }
    }

    /** Small, short, named and auto-committing by default — never the main pool's sizing. */
    @Test
    void thePoolIsSmallAndShortByDefault() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditAppendDataSource(
                        main, new AuditAppendPoolProperties(null, null))) {

            assertThat(List.<Object>of(pool.getMaximumPoolSize(), pool.getConnectionTimeout(),
                            pool.getPoolName(), pool.isAutoCommit()))
                    .containsExactly(2, 2_000L, "audit-append", true);
        }
    }

    @Test
    void theConfiguredSizeAndTimeoutAreTheOnesUsed() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditAppendDataSource(
                        main, new AuditAppendPoolProperties(3, Duration.ofMillis(750)))) {

            assertThat(List.of(pool.getMaximumPoolSize(), (int) pool.getConnectionTimeout()))
                    .containsExactly(3, 750);
        }
    }

    /** The append adapter's template writes through the pool it is given, and no other. */
    @Test
    void theTemplateWritesThroughTheAuditPool() {
        try (HikariDataSource main = mainPool();
                HikariDataSource pool = config.auditAppendDataSource(
                        main, new AuditAppendPoolProperties(null, null))) {

            assertThat(config.auditAppendJdbcTemplate(pool).getDataSource()).isSameAs(pool);
        }
    }

    /** ADR 0004's addendum sizes the pool at two or three connections; fewer is refused. */
    @Test
    void aPoolSmallerThanTwoIsRefused() {
        assertThatThrownBy(() -> new AuditAppendPoolProperties(1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APP_AUDIT_POOL_SIZE must be 2 or 3");
    }

    /** More than three would take connections the database owes the main pool. */
    @Test
    void aPoolLargerThanThreeIsRefused() {
        assertThatThrownBy(() -> new AuditAppendPoolProperties(4, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APP_AUDIT_POOL_SIZE must be 2 or 3");
    }

    @Test
    void aPoolOfTwoIsAllowed() {
        assertThat(new AuditAppendPoolProperties(2, null).size()).isEqualTo(2);
    }

    @Test
    void aPoolOfThreeIsAllowed() {
        assertThat(new AuditAppendPoolProperties(3, null).size()).isEqualTo(3);
    }

    @Test
    void aTimeoutBelowHikarisFloorIsRefusedRatherThanSilentlyRaised() {
        assertThatThrownBy(() -> new AuditAppendPoolProperties(null, Duration.ofMillis(249)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APP_AUDIT_POOL_TIMEOUT must be at least 250ms");
    }

    @Test
    void aTimeoutAtHikarisFloorIsAllowed() {
        assertThat(new AuditAppendPoolProperties(null, Duration.ofMillis(250)).timeout())
                .isEqualTo(Duration.ofMillis(250));
    }
}
