package com.example.backend.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.database.config.DatabaseConnections;
import com.example.backend.database.config.DatabaseConnectionsFixture;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The audit append pool is the connections module's pool for the app login, and sizes itself by
 * its own two settings. Built without opening a connection: the pool starts on its first append.
 */
class AuditAppendPoolConfigTests {

    private final AuditAppendPoolConfig config = new AuditAppendPoolConfig();

    /** The service's connections: its database, the app login, and no driver property. */
    private static final DatabaseConnections CONNECTIONS = DatabaseConnectionsFixture.connections();

    /** The app login, the main pool's — never the owner login, and no credential of its own. */
    @Test
    void thePoolConnectsAsTheMainPoolsLogin() {
        try (HikariDataSource pool = config.auditAppendDataSource(
                        CONNECTIONS, new AuditAppendPoolProperties(null, null))) {

            assertThat(List.of(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword(),
                            pool.getDriverClassName()))
                    .containsExactly("jdbc:sqlserver://db.internal:1433;databaseName=backend",
                            "backend_app_login", "the-app-logins", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        }
    }

    /** Small, short, named and auto-committing by default — never the main pool's sizing. */
    @Test
    void thePoolIsSmallAndShortByDefault() {
        try (HikariDataSource pool = config.auditAppendDataSource(
                        CONNECTIONS, new AuditAppendPoolProperties(null, null))) {

            assertThat(List.<Object>of(pool.getMaximumPoolSize(), pool.getConnectionTimeout(),
                            pool.getPoolName(), pool.isAutoCommit()))
                    .containsExactly(2, 2_000L, "audit-append", true);
        }
    }

    @Test
    void theConfiguredSizeAndTimeoutAreTheOnesUsed() {
        try (HikariDataSource pool = config.auditAppendDataSource(
                        CONNECTIONS, new AuditAppendPoolProperties(3, Duration.ofMillis(750)))) {

            assertThat(List.of(pool.getMaximumPoolSize(), (int) pool.getConnectionTimeout()))
                    .containsExactly(3, 750);
        }
    }

    /** The append adapter's template writes through the pool it is given, and no other. */
    @Test
    void theTemplateWritesThroughTheAuditPool() {
        try (HikariDataSource pool = config.auditAppendDataSource(
                        CONNECTIONS, new AuditAppendPoolProperties(null, null))) {

            assertThat(config.auditAppendJdbcTemplate(pool).getDataSource()).isSameAs(pool);
        }
    }

    /**
     * ADR 0004's addendum sizes the pool at two or three connections: fewer is refused, and more
     * would take connections the database owes the main pool.
     */
    @ParameterizedTest(name = "a pool of {0} is refused")
    @ValueSource(ints = {1, 4})
    void aPoolOutsideTwoToThreeIsRefused(int size) {
        assertThatThrownBy(() -> new AuditAppendPoolProperties(size, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APP_AUDIT_POOL_SIZE must be 2 or 3");
    }

    @ParameterizedTest(name = "a pool of {0} is allowed")
    @ValueSource(ints = {2, 3})
    void aPoolOfTwoOrThreeIsAllowed(int size) {
        assertThat(new AuditAppendPoolProperties(size, null).size()).isEqualTo(size);
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
