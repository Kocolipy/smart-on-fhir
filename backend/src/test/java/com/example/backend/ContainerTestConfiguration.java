package com.example.backend;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one place the test Postgres and Redis are declared: one container of each per test JVM, and
 * a database of each for every test {@code ApplicationContext}.
 *
 * <p>{@code ddl-auto: validate} plus a Flyway migration written in PostgreSQL
 * syntax (PL/pgSQL triggers, partial and expression indexes) means the test
 * database has to genuinely be Postgres — H2 cannot run {@code db/migration} as shipped, and a
 * dialect emulation layer would verify a schema the deployed service does not
 * have. The {@link DynamicPropertyRegistrar} below writes the connection onto
 * {@code spring.datasource.*} and {@code spring.data.redis.*}, so no test
 * {@code application.yaml} needs to know the containers exist.
 *
 * <p>A database per context, not one for the whole run. Contexts differ in configuration — the
 * development role mapping and its fixtures, the release gate — and some tests rely on a store
 * no other context has written: {@code RolePropagationIntegrationTests} deletes a fixture Group
 * that {@code DevelopmentRoleMappingIntegrationTests} signs in through, and seeding never repairs
 * a Group that exists. Each context therefore starts on an empty Postgres database and its own
 * Redis database number, exactly as it did when every context started containers of its own;
 * what is shared is only the containers, whose start was most of a context's boot.
 *
 * <p>The containers are started here, in a {@code static} block, rather than declared as
 * {@code @ServiceConnection} beans, because a context owns its beans' lifecycle: the first context
 * to close — {@code BackendApplicationTests} closes one on purpose — would stop a container every
 * cached context still points at. Ryuk removes them when the JVM exits.
 *
 * <p>Postgres allows 500 connections rather than its default 100, because every context the
 * cache holds keeps its own connection pool open on the one server.
 *
 * <p>It also imports {@link SeededBootstrapAdminTestConfiguration}, so every context that gets a
 * database starts with the seeded Bootstrap Admin's first-login change already completed, and
 * {@link SeededTestUserConfiguration}, so it also starts with the non-administrative
 * {@code test-user}.
 */
@Import({SeededBootstrapAdminTestConfiguration.class, SeededTestUserConfiguration.class})
public class ContainerTestConfiguration {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6-alpine"))
                    .withCommand("postgres", "-c", "max_connections=500");

    /** Redis' default of 16 numbered databases is fewer than the suite's contexts. */
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine"))
                    .withCommand("redis-server", "--databases", "128")
                    .withExposedPorts(6379);

    private static final AtomicInteger CONTEXTS = new AtomicInteger();

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * This context's stores: a Postgres database created for it, which Flyway then migrates, and
     * the next Redis database number.
     */
    @Bean
    DynamicPropertyRegistrar storesOfThisContext() throws SQLException {
        int context = CONTEXTS.incrementAndGet();
        String database = "context_" + context;
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        return registry -> {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            registry.add("spring.data.redis.database", () -> context);
        };
    }
}
