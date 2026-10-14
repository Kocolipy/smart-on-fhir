package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.observability.LogEvent.Operation;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * Failures the real PostgreSQL schema raises, recorded through a production {@code ERROR} shape —
 * a scheduled run's {@code job-end}, through {@link ScheduledJobMetrics} — and encoded by the
 * deployed ECS encoder with its customizers, then read back field by field.
 *
 * <p>Two properties, for each failure:
 *
 * <ul>
 *   <li><strong>Redaction.</strong> The refused value, the database name and the schema name
 *       appear in no emitted field — {@code message}, {@code error.message},
 *       {@code error.stack_trace} — nor anywhere else in the stream. Every statement names its
 *       table by database and schema, so the driver's text and Spring's translated message,
 *       which quotes the statement, both carry all three; only the redaction keeps them out.
 *   <li><strong>Classification.</strong> {@code error.code} is the HTTP status the failure is
 *       answered with ({@code 500} off any request), never the database's error number, and
 *       {@code error.category} is ADR 0015's mapping for the failure.
 * </ul>
 *
 * <p>Each failure is driven through the service's own pool, as the app login, unless it is one
 * the app login cannot reach: the audit trigger's refusal, which only a principal the grants let
 * through can meet, is driven as the owner login. The schemas are this class's own, made by the
 * owner login and dropped after each test, so nothing another class wrote is touched.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class DatabaseFailureLogIntegrationTests {

    private static final String JOB = "database-failure-probe";

    @Autowired
    private Environment environment;

    @Autowired
    private ScheduledJobMetrics jobs;

    /** The application's own pool, as the app login, and its translation of what the driver throws. */
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    private String database;

    /** Holds {@code probe}, which the app login may read and write. */
    private String schema;

    /** Holds {@code secret}, which the app login may not even look into. */
    private String refusedSchema;

    /** A refused value: a marker no other record could carry by chance. */
    private final String value = "refused-" + UUID.randomUUID();

    private EcsLogCapture logs;

    @BeforeEach
    void createSchemas() {
        int suffix = ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        schema = "redaction_probe_" + suffix;
        refusedSchema = "redaction_refused_" + suffix;
        database = jdbc.queryForObject("SELECT current_database()", String.class);
        asOwner("CREATE SCHEMA " + schema,
                "CREATE TABLE " + schema + ".probe (name varchar(64) UNIQUE, code varchar(8))",
                "GRANT USAGE ON SCHEMA " + schema + " TO backend_app",
                "GRANT SELECT, INSERT, UPDATE ON " + schema + ".probe TO backend_app",
                "CREATE SCHEMA " + refusedSchema,
                "CREATE TABLE " + refusedSchema + ".secret (name varchar(64))");
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void dropSchemas() {
        logs.close();
        asOwner("DROP SCHEMA " + schema + " CASCADE", "DROP SCHEMA " + refusedSchema + " CASCADE");
    }

    // ---- the four failures that quote what they refused ---------------------------------------

    @Test
    void anUntranslatedUniqueViolationIsADatabaseErrorQuotingNothing() {
        jdbc.execute("INSERT INTO " + probe() + " (name) VALUES ('" + value + "')");

        JsonNode end = failedRun("INSERT INTO " + probe() + " (name) VALUES ('" + value + "')");

        assertRedacted(end, SqlFailures.UNIQUE_VIOLATION);
        assertClassified(end, "database");
    }

    @Test
    void aValueTooLongIsADatabaseErrorQuotingNothing() {
        JsonNode end = failedRun("INSERT INTO " + probe() + " (name, code) VALUES ('" + value
                + "', '" + value + "')");

        assertRedacted(end, SqlFailures.VALUE_TOO_LONG);
        assertClassified(end, "database");
    }

    @Test
    void aConversionErrorIsADatabaseErrorQuotingNothing() {
        JsonNode end = failedRun("SELECT CAST('" + value + "' AS integer) FROM " + probe());

        assertRedacted(end, SqlFailures.INVALID_TEXT_REPRESENTATION);
        assertClassified(end, "database");
    }

    /**
     * The app login refused a schema it has no rights in: the database refused our rights. The
     * statement runs on the service's own pool, as the app login every request connects as.
     */
    @Test
    void aPermissionRefusalIsACertAuthErrorQuotingNothing() {
        JsonNode end = failedRun("SELECT name FROM " + database + "." + refusedSchema
                + ".secret WHERE name = '" + value + "'");

        assertRedacted(end, SqlFailures.PERMISSION_DENIED);
        assertClassified(end, "cert/auth");
    }

    // ---- the other failures the suite can produce ---------------------------------------------

    /**
     * A row lock another transaction holds — the owner login's, on a connection outside the
     * service's pools — asked for without waiting: a lock not acquired.
     */
    @Test
    void aLockThatCouldNotBeAcquiredIsADatabaseError() {
        jdbc.execute("INSERT INTO " + probe() + " (name) VALUES ('" + value + "')");
        DatabaseLogins.Login holder = logins.owner();
        JdbcTemplate holding = holder.jdbc();

        JsonNode end = holder.transactions().execute(status -> {
            status.setRollbackOnly();
            holding.execute("SELECT name FROM " + probe() + " WHERE name = '" + value
                    + "' FOR UPDATE");
            return failedRun("SELECT name FROM " + probe() + " WHERE name = '" + value
                    + "' FOR UPDATE NOWAIT");
        });

        assertRedacted(end, SqlFailures.LOCK_NOT_AVAILABLE);
        assertClassified(end, "database");
    }

    /**
     * The audit table's append-only trigger refusing a delete (ADR 0004): a database failure to
     * investigate, never a permission refusal, so the trigger cannot be mistaken for a grant that
     * is missing. The app login has no grant to delete with, so the grant would refuse it first
     * ({@code 42501}); the owner login owns the table, so its delete reaches the trigger, which
     * refuses it as an administrator of the retention role rather than the job it is reserved
     * for. The event is inserted and the delete refused in one transaction, which the refusal
     * rolls back, so the append-only table keeps nothing of the test.
     */
    @Test
    void anAuditTriggerRefusalIsADatabaseError() {
        DatabaseLogins.Login owner = logins.owner();
        JdbcTemplate asOwner = owner.jdbc();

        JsonNode end = failedRun(() -> owner.transactions().executeWithoutResult(status -> {
            UUID event = UUID.randomUUID();
            asOwner.update("INSERT INTO audit_events (id, occurred_at, operation, outcome,"
                    + " resource_type, status_class) VALUES (?, now(), 'LOGOUT',"
                    + " 'SUCCESS', 'User', 'ok')", event);
            asOwner.update("DELETE FROM audit_events WHERE id = ?", event);
        }));

        assertThat(end.at("/error/message").asText())
                .isEqualTo(SqlFailures.AUDIT_TRIGGER_REFUSAL.redacted());
        assertClassified(end, "database");
    }

    /** A login the database refused — this service's own credential — needs its configuration checked. */
    @Test
    void aRefusedDatabaseLoginIsACertAuthError() {
        JdbcTemplate refused = new JdbcTemplate(new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"), value));

        JsonNode end = failedRun(() -> refused.execute("SELECT 1"));

        assertThat(end.at("/error/message").asText())
                .isEqualTo(SqlFailures.INVALID_PASSWORD.redacted());
        assertClassified(end, "cert/auth");
    }

    // ---- harness -----------------------------------------------------------------------------

    private String probe() {
        return database + "." + schema + ".probe";
    }

    private JsonNode failedRun(String statement) {
        return failedRun(() -> jdbc.execute(statement));
    }

    /**
     * Runs {@code body} as one run of a scheduled job, which the database fails, and returns the
     * run's one {@code job-end} as encoded.
     */
    private JsonNode failedRun(Runnable body) {
        logs.reset();
        Runnable run = jobs.instrumentLocked(JOB, Operation.DORMANCY, () -> {
            body.run();
            return () -> false;
        });

        assertThatThrownBy(run::run).isInstanceOf(RuntimeException.class);

        List<JsonNode> ends = logs.records().stream()
                .filter(record -> JOB.equals(record.at("/batch/job/name").asText()))
                .filter(record -> "Scheduled job failed".equals(record.at("/message").asText()))
                .toList();
        assertThat(ends).as("the run's failed job-end").hasSize(1);
        return ends.getFirst();
    }

    /**
     * No field the record emits — and nothing else in the stream — carries the refused value,
     * the database name or the schema names, while the record still names the failure by its
     * SQLState and keeps the stack.
     */
    private void assertRedacted(JsonNode end, SqlFailures.Failure failure) {
        String state = failure.sqlState();
        List<String> forbidden = List.of(value, database, schema, refusedSchema);
        for (String field : List.of("/message", "/error/message", "/error/stack_trace")) {
            assertThat(end.at(field).asText()).as(field).doesNotContain(forbidden);
        }
        assertThat(logs.lines()).as("the whole stream").doesNotContain(forbidden);
        assertThat(end.at("/error/message").asText()).contains("(SQLState " + state + ")");
        assertThat(end.at("/error/stack_trace").asText())
                .contains("Caused by: ")
                .contains("(SQLState " + state + ")")
                .contains("\tat " + getClass().getName());
    }

    private static void assertClassified(JsonNode end, String category) {
        assertThat(end.at("/error/code").asInt()).as("error.code is the HTTP status").isEqualTo(500);
        assertThat(end.at("/error/category").asText()).isEqualTo(category);
    }

    private void asOwner(String... statements) {
        JdbcTemplate owner = logins.owner().jdbc();
        for (String sql : statements) {
            owner.execute(sql);
        }
    }
}
