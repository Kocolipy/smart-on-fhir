package com.example.backend;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
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
 * {@code spring.datasource.*}, {@code app.datasource.retention.*}, {@code spring.flyway.*} and
 * {@code spring.data.redis.*}, so no test {@code application.yaml} needs to know the containers
 * exist.
 *
 * <p>A database per context, not one for the whole run. Contexts differ in configuration — the
 * development role mapping and its fixtures, the release gate — and some tests rely on a store
 * no other context has written: {@code RolePropagationIntegrationTests} deletes a fixture Group
 * that {@code DevelopmentRoleMappingIntegrationTests} signs in through, and seeding never repairs
 * a Group that exists. Each context therefore starts on an empty Postgres database and its own
 * Redis database number, exactly as it did when every context started containers of its own;
 * what is shared is only the containers, whose start was most of a context's boot.
 *
 * <p><strong>The deployment's three database logins, not the container's superuser.</strong> The
 * service's main pool connects as the app login and its retention pool as the retention login,
 * and Flyway migrates as the owner login, which owns each context's database and administers the
 * two NOLOGIN roles ({@code WITH ADMIN OPTION}) — the shape the deployment's bootstrap gives them.
 * The migrations make the app and retention logins members of their roles, through the
 * placeholders registered below. So every statement a test drives through the service runs with
 * a deployment's privileges, and a grant a migration forgot fails here first. A test that must
 * speak as another principal goes through {@link DatabaseLogins}. Logins and roles are
 * cluster-wide, so they are created once per container, in the {@code static} block.
 *
 * <p>The containers are started here, in a {@code static} block, rather than declared as
 * {@code @ServiceConnection} beans, because a context owns its beans' lifecycle: the first context
 * to close — {@code BackendApplicationTests} closes one on purpose — would stop a container every
 * cached context still points at. Ryuk removes them when the JVM exits.
 *
 * <p>Postgres allows 500 connections rather than its default 100, because every context the
 * cache holds keeps its own connection pools open on the one server.
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

    private static final AtomicInteger DATABASES = new AtomicInteger();

    /** The owner login: owns each context's database and runs its migrations. */
    public static final String OWNER_LOGIN = "backend_owner";

    /** The app login: the service's main pool and the audit append pool. */
    public static final String APP_LOGIN = "backend_app_login";

    /** The retention login: the audit retention job's pool. */
    public static final String RETENTION_LOGIN = "backend_retention_login";

    /** Generated per test JVM, as a deployment's are per stack, and never written anywhere. */
    public static final String OWNER_PASSWORD = UUID.randomUUID().toString();

    static final String APP_PASSWORD = UUID.randomUUID().toString();

    static final String RETENTION_PASSWORD = UUID.randomUUID().toString();

    static {
        POSTGRES.start();
        REDIS.start();
        createTheDatabaseLogins();
    }

    static String superuserName() {
        return POSTGRES.getUsername();
    }

    static String superuserPassword() {
        return POSTGRES.getPassword();
    }

    /**
     * The three logins and the two roles, as the deployment's bootstrap creates them: the owner
     * administers both roles, and no other membership is granted here — the migrations grant the
     * app and retention logins theirs. The names are this class's constants and the passwords
     * UUIDs, so the literals need no escaping.
     */
    private static void createTheDatabaseLogins() {
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute(createLogin(OWNER_LOGIN, OWNER_PASSWORD));
            statement.execute(createLogin(APP_LOGIN, APP_PASSWORD));
            statement.execute(createLogin(RETENTION_LOGIN, RETENTION_PASSWORD));
            statement.execute("CREATE ROLE backend_app NOLOGIN");
            statement.execute("CREATE ROLE backend_audit_retention NOLOGIN");
            statement.execute("GRANT backend_app, backend_audit_retention TO " + OWNER_LOGIN
                    + " WITH ADMIN OPTION");
        } catch (SQLException failure) {
            throw new IllegalStateException("the test database logins could not be created", failure);
        }
    }

    private static String createLogin(String name, String password) {
        return "CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'";
    }

    /**
     * This context's stores: a Postgres database created for it and owned by the owner login,
     * which Flyway migrates as that login, and the next Redis database number.
     */
    @Bean
    DynamicPropertyRegistrar storesOfThisContext() throws SQLException {
        int context = CONTEXTS.incrementAndGet();
        String url = emptyDatabase();
        return registry -> {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> APP_LOGIN);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("app.datasource.retention.username", () -> RETENTION_LOGIN);
            registry.add("app.datasource.retention.password", () -> RETENTION_PASSWORD);
            registry.add("spring.flyway.user", () -> OWNER_LOGIN);
            registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
            registry.add("spring.flyway.placeholders[app_login]", () -> APP_LOGIN);
            registry.add("spring.flyway.placeholders[retention_login]", () -> RETENTION_LOGIN);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            registry.add("spring.data.redis.database", () -> context);
        };
    }

    /**
     * A new, empty database owned by the owner login, as a deployment's bootstrap leaves one, and
     * its JDBC URL — each context's, and a test's that migrates a database itself rather than
     * through a context. Named from a counter, so the statement holds no caller's text.
     */
    public static String emptyDatabase() throws SQLException {
        String name = "database_" + DATABASES.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER " + OWNER_LOGIN);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
    }

    /** This context's database as each login sees it, for the tests that need one. */
    @Bean
    DatabaseLogins databaseLogins(Environment environment) {
        return new DatabaseLogins(environment);
    }
}
