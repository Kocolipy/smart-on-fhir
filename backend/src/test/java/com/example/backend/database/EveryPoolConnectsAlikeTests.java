package com.example.backend.database;

import static com.example.backend.database.config.DatabaseConnectionsFixture.asTheDriverReads;
import static com.example.backend.database.config.DatabaseConnectionsFixture.driverProperties;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.config.AuditAppendPoolConfig;
import com.example.backend.audit.config.AuditRetentionPoolConfig;
import com.example.backend.database.config.DatabaseConnectionsFixture;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Every pool the service declares reaches the database as the main pool does: through the same
 * URL and driver, and with every connection property a deployment sets on
 * {@code spring.datasource.hikari.data-source-properties} — where TLS, the trust store and the
 * login timeout are given to the driver (ADR 0015). A pool built without them would connect
 * unencrypted, or not at all, the day they leave the URL.
 *
 * <p>The pools are built, never started: nothing here opens a connection.
 */
class EveryPoolConnectsAlikeTests {

    private static final List<String> POOLS =
            List.of("dataSource", "auditAppendDataSource", "auditRetentionDataSource");

    private final ApplicationContextRunner contexts = DatabaseConnectionsFixture.mainPoolContexts()
            .withUserConfiguration(AuditAppendPoolConfig.class, AuditRetentionPoolConfig.class)
            .withPropertyValues(DatabaseConnectionsFixture.retentionLoginSettings());

    /** The settings SC14 moves out of the URL, under the driver's own spelling of each name. */
    @Test
    void everyPoolCarriesTheMainPoolsConnectionProperties() {
        contexts.withPropertyValues(
                        "spring.datasource.hikari.data-source-properties.sslmode=verify-full",
                        "spring.datasource.hikari.data-source-properties.[sslrootcert]=/etc/rds-ca.pem",
                        "spring.datasource.hikari.data-source-properties.[loginTimeout]=15")
                .run(context -> assertThat(POOLS.stream()
                                .map(pool -> context.getBean(pool, HikariDataSource.class)
                                        .getDataSourceProperties())
                                .toList())
                        .containsOnly(driverProperties(Map.of("sslmode", "verify-full",
                                "sslrootcert", "/etc/rds-ca.pem", "loginTimeout", "15"))));
    }

    /**
     * The deployed stack's trust-store password reaches every pool's driver from the process
     * environment alone (infra/README.md, "TLS to the database"): {@code start.sh} exports it as
     * this variable after reading it from Secrets Manager, so it is in no file and no URL. The
     * environment-variable binding lower-cases the key; the driver matches property names
     * case-insensitively, so it reads the value as its {@code trustStorePassword}.
     */
    @Test
    void theDeploymentsTrustStorePasswordReachesEveryPoolsDriverFromTheEnvironment() {
        contexts.withInitializer(DatabaseConnectionsFixture.environment(Map.of(
                        "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD",
                        "the-trust-stores")))
                .run(context -> {
                    List<String> read = new ArrayList<>();
                    for (String pool : POOLS) {
                        read.add(asTheDriverReads(context.getBean(pool, HikariDataSource.class)
                                .getDataSourceProperties(), "trustStorePassword"));
                    }
                    assertThat(read).containsExactly(
                            "the-trust-stores", "the-trust-stores", "the-trust-stores");
                });
    }

    /** One database for all of them, through one driver. */
    @Test
    void everyPoolReachesTheMainPoolsDatabaseThroughItsDriver() {
        contexts.run(context -> assertThat(POOLS.stream()
                        .map(pool -> context.getBean(pool, HikariDataSource.class))
                        .map(pool -> List.of(pool.getJdbcUrl(), pool.getDriverClassName()))
                        .toList())
                .containsOnly(List.of("jdbc:sqlserver://db.internal:1433;databaseName=backend",
                        "com.microsoft.sqlserver.jdbc.SQLServerDriver")));
    }

    /**
     * Nothing runs on a new connection of another pool, whatever the main pool was given: each
     * login's own privileges are its pool's, and a statement copied from elsewhere could only
     * widen them.
     */
    @Test
    void theMainPoolsConnectionInitSqlReachesNoOtherPool() {
        contexts.withPropertyValues("spring.datasource.hikari.connection-init-sql=SELECT 1")
                .run(context -> assertThat(POOLS.stream()
                                .map(pool -> String.valueOf(context.getBean(
                                        pool, HikariDataSource.class).getConnectionInitSql()))
                                .toList())
                        .containsExactly("SELECT 1", "null", "null"));
    }
}
