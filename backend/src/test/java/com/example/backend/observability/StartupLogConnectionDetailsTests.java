package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.BackendApplication;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.database.config.MigrationStep;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A deployment's start, as a deployment starts it — the migrate step ({@link MigrationStep}'s own
 * {@code main}) on an empty database, then the service ({@link BackendApplication}'s), each on its
 * own {@code application.yaml}, with the connection given only by the environment variables a
 * deployment sets and each process given only its own logins — writes no log line, from the
 * migrate step's first to the service's shutdown, that carries the JDBC URL, the database host
 * or the database name.
 *
 * <p>Child JVMs, for the reason {@link LogFileTests} has one: logging is JVM-global, and a second
 * application started in this one would reinitialize every cached test context's logging. Their
 * classpath is this JVM's without the test classes, so the configuration they read is the main
 * {@code application.yaml}, not the test resources' copy that shadows it here. This class joins
 * the shared test context only to borrow the containers' coordinates and the logins from it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class StartupLogConnectionDetailsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code ApplicationLifecycleLog}'s record once the application is ready. */
    private static final String STARTED = "\"message\":\"Application started\"";

    /** Flyway's own record that it migrated: the migrate step's migration-time output. */
    private static final String MIGRATED = "Successfully applied";

    /** The loggers {@code log-levels.yaml} pins for the connection's sake. */
    private static final List<String> PINNED = List.of(
            "com.zaxxer.hikari", "com.microsoft.sqlserver.jdbc", "org.flywaydb",
            "org.hibernate.orm.connections.pooling");

    /**
     * Every logger at {@code DEBUG} but Tomcat's: its own class loader cannot start with its
     * logger at {@code DEBUG} (it logs while loading the classes logging needs), so it alone stays
     * at its default.
     */
    private static final Map<String, String> ROOT_AT_DEBUG = Map.of(
            "LOGGING_LEVEL_ROOT", "DEBUG",
            "LOGGING_LEVEL_ORG_APACHE_CATALINA", "INFO",
            "LOGGING_LEVEL_ORG_APACHE_TOMCAT", "INFO");

    /** A retention run every second, so the retention pool starts while the service runs. */
    private static final Map<String, String> RETENTION_EVERY_SECOND =
            Map.of("APP_AUDIT_RETENTION_SCHEDULE", "* * * * * *");

    @Autowired
    private Environment environment;

    @Test
    void noMigrationStartupOrShutdownLineNamesTheJdbcUrlTheHostOrTheDatabase() throws Exception {
        String url = ContainerTestConfiguration.emptyDatabase();

        List<String> migration = migrate(url, Map.of());
        List<String> service = startAndStop(url, Map.of(), lines -> { });

        assertThat(migration).as("the migrate step migrated").anyMatch(line -> line.contains(MIGRATED));
        assertThat(service).as("the service started").anyMatch(line -> line.contains(STARTED));
        assertThat(both(migration, service))
                .as("lines naming the JDBC URL, the database host or the database")
                .filteredOn(line -> namesTheConnection(line, url))
                .isEmpty();
    }

    /**
     * The connection libraries' loggers are pinned in {@code log-levels.yaml}, so even a migrate
     * step and a service with every other logger turned up to {@code DEBUG} — Hikari's
     * configuration dump, the driver's connection trace, Flyway's and Hibernate's database
     * information — name no connection detail through them. Hikari still names each of the
     * service's three pools, which the start alone does not all open: the audit append pool
     * opens on the first fail-open append, which a refused login makes, and the retention pool
     * on the first retention run.
     */
    @Test
    void withTheRootLoggerAtDebugThePinnedConnectionLoggersNameNoConnectionDetail()
            throws Exception {
        String url = ContainerTestConfiguration.emptyDatabase();
        Map<String, String> settings = new HashMap<>(ROOT_AT_DEBUG);
        settings.putAll(RETENTION_EVERY_SECOND);

        List<String> migration = migrate(url, ROOT_AT_DEBUG);
        List<String> service = startAndStop(url, settings, lines -> {
            refuseALogin(lines);
            awaitLine(lines, poolStarted("audit-append"));
            awaitLine(lines, poolStarted("retention"));
        });

        assertThat(migration).as("the migrate step migrated").anyMatch(line -> line.contains(MIGRATED));
        assertThat(List.of("app", "audit-append", "retention"))
                .as("the pools Hikari names as each starts")
                .allMatch(pool -> service.stream().anyMatch(line -> line.contains(poolStarted(pool))));
        assertThat(both(migration, service)).as("pinned loggers' lines naming the connection")
                .filteredOn(line -> PINNED.stream().anyMatch(
                        logger -> line.contains("\"logger\":\"" + logger)))
                .filteredOn(line -> namesTheConnection(line, url))
                .isEmpty();
    }

    /** Hikari's line once a pool has opened its connections, which names the pool alone. */
    private static String poolStarted(String pool) {
        return "\"message\":\"" + pool + " - Start completed.\"";
    }

    /**
     * Whether {@code line} names the connection of {@code url}, a
     * {@code jdbc:sqlserver://host:port;databaseName=…;…} URL: the URL itself, the server, or the
     * database.
     */
    private static boolean namesTheConnection(String line, String url) {
        String[] parts = url.substring("jdbc:".length()).split(";");
        URI server = URI.create(parts[0]);
        String database = Arrays.stream(parts)
                .filter(part -> part.startsWith("databaseName="))
                .map(part -> part.substring("databaseName=".length()))
                .findFirst()
                .orElseThrow();
        return line.contains("jdbc:")
                || line.contains(server.getHost() + ":" + server.getPort())
                || line.contains(server.getHost())
                || line.contains(database);
    }

    private static List<String> both(List<String> first, List<String> second) {
        List<String> lines = new ArrayList<>(first);
        lines.addAll(second);
        return lines;
    }

    // ---- harness -----------------------------------------------------------------------------

    /**
     * Runs the migrate step on {@code url} in a child JVM, as a deployment's migrate step: the
     * owner login, and the other two logins' names only. Returns every line it wrote, once it has
     * exited with status zero.
     */
    private List<String> migrate(String url, Map<String, String> settings)
            throws IOException, InterruptedException {
        Map<String, String> env = new HashMap<>(settings);
        env.put("DATABASE_URL", url);
        env.put("DATABASE_OWNER_USERNAME", ContainerTestConfiguration.OWNER_LOGIN);
        env.put("DATABASE_OWNER_PASSWORD", ContainerTestConfiguration.OWNER_PASSWORD);
        env.put("DATABASE_USERNAME", environment.getRequiredProperty("spring.datasource.username"));
        env.put("DATABASE_RETENTION_USERNAME",
                environment.getRequiredProperty("app.datasource.retention.username"));
        Process process = start(MigrationStep.class, env);
        List<String> lines = new CopyOnWriteArrayList<>();
        Thread reader = reading(process, lines, new CountDownLatch(0), List.of());
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("the migrate step exited").isTrue();
        } finally {
            stop(process, reader);
        }
        assertThat(process.exitValue()).as("the migrate step's exit status").isZero();
        return lines;
    }

    /**
     * Runs the service on {@code url} in a child JVM, as a deployment runs it — no profile, so
     * Flyway off, and the app and retention logins only — until it reports itself started, then
     * runs {@code whileRunning} against it, then stops it as a deployment does (SIGTERM) and
     * returns every line it wrote, its shutdown's included.
     */
    private List<String> startAndStop(String url, Map<String, String> settings,
            WhileRunning whileRunning) throws Exception {
        Map<String, String> env = new HashMap<>(settings);
        env.put("DATABASE_URL", url);
        env.put("DATABASE_USERNAME", environment.getRequiredProperty("spring.datasource.username"));
        env.put("DATABASE_PASSWORD", environment.getRequiredProperty("spring.datasource.password"));
        env.put("DATABASE_RETENTION_USERNAME",
                environment.getRequiredProperty("app.datasource.retention.username"));
        env.put("DATABASE_RETENTION_PASSWORD",
                environment.getRequiredProperty("app.datasource.retention.password"));
        env.put("REDIS_HOST", environment.getRequiredProperty("spring.data.redis.host"));
        env.put("REDIS_PORT", environment.getRequiredProperty("spring.data.redis.port"));
        env.put("SERVER_PORT", "0");
        // The Bootstrap Admin's first password, which has no fallback; and no development
        // fixtures, which a start with no profile refuses (ScimSeedConfig).
        env.put("APP_BOOTSTRAP_PASSWORD", "Startup-Probe-P@ssw0rd");
        Process process = start(BackendApplication.class, env);

        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch startedOrEnded = new CountDownLatch(1);
        Thread reader = reading(process, lines, startedOrEnded, List.of(STARTED));
        try {
            assertThat(startedOrEnded.await(180, TimeUnit.SECONDS))
                    .as("the service started or exited").isTrue();
            whileRunning.against(lines);
        } finally {
            stop(process, reader);
        }
        return lines;
    }

    /** What a test does to the running service, given the lines it has written so far. */
    @FunctionalInterface
    private interface WhileRunning {
        void against(List<String> lines) throws Exception;
    }

    /**
     * A login the running service refuses — an unknown username, with the session's CSRF token —
     * which it records as a fail-open audit event, on the audit append pool.
     */
    private static void refuseALogin(List<String> lines) throws Exception {
        String port = awaitLine(lines, "Tomcat started on port ")
                .replaceAll(".*Tomcat started on port (\\d+).*", "$1");
        URI base = URI.create("http://localhost:" + port);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            JsonNode csrf = JSON.readTree(client.send(
                    HttpRequest.newBuilder(base.resolve("/api/auth/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            HttpResponse<String> refused = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                            .header("Content-Type", "application/json")
                            .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"no-such-user\",\"password\":\"wrong\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(refused.statusCode()).as("the refused login's status").isEqualTo(401);
        }
    }

    /** The first line the running service has written, or writes within 60 s, containing {@code text}. */
    private static String awaitLine(List<String> lines, String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            for (String line : lines) {
                if (line.contains(text)) {
                    return line;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the service wrote no line containing " + text);
    }

    /**
     * Launches {@code main} in a child JVM with {@code settings} as the only database, profile and
     * logging settings in its environment: whatever this JVM's environment holds of them is
     * removed first, so each process holds exactly the logins a deployment gives it.
     */
    private static Process start(Class<?> main, Map<String, String> settings) throws IOException {
        // Test harness only: launches this JVM's own java binary on its own classpath with a
        // constant class name. No argument is request-controlled or comes from outside the test,
        // so there is nothing to inject, and both rules' concern (a service spawning processes
        // from input) does not apply.
        // nosemgrep: be-process-execution, java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", mainClasspath(),
                main.getName())
                .redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("DATABASE_")
                || name.startsWith("LOGGING_LEVEL_") || name.equals("SPRING_PROFILES_ACTIVE")
                || name.equals("LOG_FILE") || name.equals("LOG_STRUCTURED_FORMAT"));
        builder.environment().putAll(settings);
        return builder.start();
    }

    /**
     * Reads {@code process}'s output into {@code lines} on a thread of its own, counting
     * {@code awaitedOrEnded} down once a line containing each of {@code awaited} has been read, or
     * once the output ends.
     */
    private static Thread reading(Process process, List<String> lines,
            CountDownLatch awaitedOrEnded, List<String> awaited) {
        Thread reader = new Thread(() -> {
            List<String> unseen = new ArrayList<>(awaited);
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    lines.add(line);
                    unseen.removeIf(line::contains);
                    if (unseen.isEmpty()) {
                        awaitedOrEnded.countDown();
                    }
                }
            } catch (IOException ended) {
                // The stream closes with the process; what was read is what it wrote.
            } finally {
                awaitedOrEnded.countDown();
            }
        });
        reader.start();
        return reader;
    }

    /** Stops {@code process} as a deployment does (SIGTERM), and waits for its last line. */
    private static void stop(Process process, Thread reader) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        reader.join(TimeUnit.SECONDS.toMillis(10));
    }

    /** This JVM's classpath without the test classes, so the main configuration is the one read. */
    private static String mainClasspath() {
        List<String> entries = Arrays.stream(
                        System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.endsWith("test-classes")
                        && !entry.endsWith("test-classes" + File.separator))
                .toList();
        assertThat(entries).as("the main classes are on the classpath")
                .anyMatch(entry -> entry.endsWith("classes") || entry.endsWith("classes" + File.separator));
        return entries.stream().collect(Collectors.joining(File.pathSeparator));
    }
}
