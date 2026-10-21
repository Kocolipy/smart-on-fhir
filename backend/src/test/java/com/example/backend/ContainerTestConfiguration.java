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
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one place the test SQL Server and Redis are declared: one container of each per test JVM,
 * and a database of each for every test {@code ApplicationContext}.
 *
 * <p>{@code ddl-auto: validate} plus a Flyway migration written in T-SQL (an {@code INSTEAD OF}
 * trigger, filtered indexes, a persisted computed column) means the test database has to genuinely
 * be SQL Server — H2 cannot run {@code db/migration} as shipped, and a dialect emulation layer
 * would verify a schema the deployed service does not have. The {@link DynamicPropertyRegistrar}
 * below writes the connection onto {@code spring.datasource.*}, {@code app.datasource.retention.*},
 * {@code spring.flyway.*} and {@code spring.data.redis.*}, so no test {@code application.yaml}
 * needs to know the containers exist.
 *
 * <p>A database per context, not one for the whole run. Contexts differ in configuration — the
 * development role mapping and its fixtures, the release gate — and some tests rely on a store
 * no other context has written: {@code RolePropagationIntegrationTests} deletes a fixture Group
 * that {@code DevelopmentRoleMappingIntegrationTests} signs in through, and seeding never repairs
 * a Group that exists. Each context therefore starts on an empty database and its own Redis
 * database number, exactly as it did when every context started containers of its own; what is
 * shared is only the containers, whose start was most of a context's boot.
 *
 * <p><strong>Each database is created as the deployment creates one</strong> (ADR 0013): with the
 * collation {@value #COLLATION}, compatibility level 160, {@code READ_COMMITTED_SNAPSHOT} and
 * {@code ALLOW_SNAPSHOT_ISOLATION} on, and owned by the owner login. Those are creation options,
 * set here because no migration can set them: {@code READ_COMMITTED_SNAPSHOT} cannot change inside
 * the transaction Flyway migrates in (ADR 0013, V6).
 *
 * <p><strong>The deployment's three database logins, not the container's {@code sa}.</strong>
 * The service's main pool connects as the app login and its retention pool as the retention
 * login, and Flyway migrates as the owner login, which owns each context's database — the shape
 * the deployment's bootstrap gives them. The migration creates the app and retention logins'
 * database users and makes each a member of its role, through the placeholders registered below.
 * So every statement a test drives through the service runs with a deployment's privileges, and a
 * grant the migration forgot fails here first. A test that must speak as another principal goes
 * through {@link DatabaseLogins}. Logins are server principals, so they are created once per
 * container, in the {@code static} block; the roles are each database's own, and the migration
 * creates them.
 *
 * <p>The containers are started here, in a {@code static} block, rather than declared as
 * {@code @ServiceConnection} beans, because a context owns its beans' lifecycle: the first context
 * to close — {@code BackendApplicationTests} closes one on purpose — would stop a container every
 * cached context still points at. Ryuk removes them when the JVM exits.
 *
 * <p>It also imports {@link SeededBootstrapAdminTestConfiguration}, so every context that gets a
 * database starts with the seeded Bootstrap Admin's first-login change already completed, and
 * {@link SeededTestUserConfiguration}, so it also starts with the non-administrative
 * {@code test-user}.
 */
@Import({SeededBootstrapAdminTestConfiguration.class, SeededTestUserConfiguration.class})
public class ContainerTestConfiguration {

    /**
     * SQL Server 2022 CU27 ({@code 2022-CU27-ubuntu-22.04}, Developer Edition, amd64), pinned by
     * digest as {@code compose.yaml} pins it; see {@code /README.md}, "Toolchain pins".
     */
    static final String SQL_SERVER_IMAGE = "mcr.microsoft.com/mssql/server@sha256:"
            + "4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090";

    /** The collation every database is created with (ADR 0013). */
    static final String COLLATION = "Latin1_General_100_BIN2_UTF8";

    /**
     * Developer Edition's licence is accepted for development and test use only.
     *
     * <p>The server keeps its default collation: only each database is created with
     * {@value #COLLATION}. A server collated {@code BIN2_UTF8} ({@code MSSQL_COLLATION}) fails to
     * load the internal extended procedures behind {@code sp_getapplock} and a T-SQL
     * {@code sp_executesql} ("Could not load the DLL (server internal)", error 17750) on this
     * image. Nothing of the service's own calls either any more — the seed lock is a row lock
     * (ADR 0013's 2026-10-16 addendum) — but Flyway takes {@code sp_getapplock} to guard every
     * migration, so on such a server no context could migrate its database.
     */
    private static final MSSQLServerContainer SQL_SERVER =
            new MSSQLServerContainer(DockerImageName.parse(SQL_SERVER_IMAGE)
                            .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server"))
                    .acceptLicense();

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

    /**
     * Generated per test JVM, as a deployment's are per stack, and never written anywhere. A UUID
     * meets SQL Server's password policy (lower-case letters, digits and a symbol), and holds no
     * quote, so the literal needs no escaping.
     */
    public static final String OWNER_PASSWORD = UUID.randomUUID().toString();

    /** The app login's, generated as the owner's is; for a test that connects as the service. */
    public static final String APP_PASSWORD = UUID.randomUUID().toString();

    static final String RETENTION_PASSWORD = UUID.randomUUID().toString();

    static {
        SQL_SERVER.start();
        REDIS.start();
        createTheDatabaseLogins();
    }

    static String superuserName() {
        return SQL_SERVER.getUsername();
    }

    static String superuserPassword() {
        return SQL_SERVER.getPassword();
    }

    /**
     * The three logins, as the deployment's bootstrap creates them. The names are this class's
     * constants and the passwords UUIDs, so the literals need no escaping.
     */
    private static void createTheDatabaseLogins() {
        try (Connection admin = asSuperuser("master");
                Statement statement = admin.createStatement()) {
            statement.execute(createLogin(OWNER_LOGIN, OWNER_PASSWORD));
            statement.execute(createLogin(APP_LOGIN, APP_PASSWORD));
            statement.execute(createLogin(RETENTION_LOGIN, RETENTION_PASSWORD));
        } catch (SQLException failure) {
            throw new IllegalStateException("the test database logins could not be created", failure);
        }
    }

    private static String createLogin(String name, String password) {
        return "CREATE LOGIN " + name + " WITH PASSWORD = N'" + password + "'";
    }

    /**
     * This context's stores: a database created for it and owned by the owner login, which Flyway
     * migrates as that login, and the next Redis database number.
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
     * A new, empty database with the deployment's creation options, and the owner login a member
     * of its {@code db_owner} role under a database user of its own name, as a deployment's
     * bootstrap leaves one (infra's {@code db-bootstrap.sql}, and the local init), and its JDBC
     * URL — each context's, and a test's that migrates a database itself rather than through a
     * context. The database's owner ({@code dbo}) stays the login that created it: on RDS that is
     * the master user, which cannot hand a database to another login. Named from a counter, so
     * the statements hold no caller's text.
     */
    public static String emptyDatabase() throws SQLException {
        String name = "database_" + DATABASES.incrementAndGet();
        try (Connection admin = asSuperuser("master");
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " COLLATE " + COLLATION);
            statement.execute("ALTER DATABASE " + name + " SET COMPATIBILITY_LEVEL = 160");
            statement.execute("ALTER DATABASE " + name + " SET READ_COMMITTED_SNAPSHOT ON");
            statement.execute("ALTER DATABASE " + name + " SET ALLOW_SNAPSHOT_ISOLATION ON");
            statement.execute("USE " + name + "; CREATE USER " + OWNER_LOGIN + " FOR LOGIN "
                    + OWNER_LOGIN + " WITH DEFAULT_SCHEMA = dbo; ALTER ROLE db_owner ADD MEMBER "
                    + OWNER_LOGIN);
        }
        return url(name);
    }

    /**
     * The URL of {@code database} on the test server. The container's certificate is
     * self-signed, so a test connection trusts it, which only test and local configuration may
     * ({@code arch.TrustServerCertificateTest}); it is encrypted all the same, by the driver's
     * default and by {@code database.yaml}'s {@code encrypt}, which the URL leaves to them.
     */
    private static String url(String database) {
        return "jdbc:sqlserver://" + SQL_SERVER.getHost() + ":"
                + SQL_SERVER.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)
                + ";databaseName=" + database + ";trustServerCertificate=true";
    }

    private static Connection asSuperuser(String database) throws SQLException {
        return DriverManager.getConnection(
                url(database), SQL_SERVER.getUsername(), SQL_SERVER.getPassword());
    }

    /** This context's database as each login sees it, for the tests that need one. */
    @Bean
    DatabaseLogins databaseLogins(Environment environment) {
        return new DatabaseLogins(environment);
    }
}
