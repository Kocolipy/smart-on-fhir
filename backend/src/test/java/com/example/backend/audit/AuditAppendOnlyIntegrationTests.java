package com.example.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.SessionCsrf;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.InMemorySessionRegistryConfiguration;
import com.example.backend.audit.application.AuditRetentionService;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The audit table's guarantees, asserted where they are actually enforced: in the
 * database.
 *
 * <p>Three things are proved here, and none of them is a property of Java code:
 *
 * <ul>
 *   <li>a write whose audit insert cannot commit is rolled back with it;
 *   <li>a failure event that cannot be appended raises an operational alert and
 *       leaves the original response exactly as it was;
 *   <li>the application's database role may insert and read audit rows and may not
 *       update or delete one, while a separate role reserved for retention may.
 * </ul>
 *
 * <p>The insert failure is forced with a trigger installed for the length of one
 * test rather than by substituting a repository. A stub would prove that a fake
 * threw where a test told it to; a trigger proves the transaction really does not
 * commit, which is the claim.
 *
 * <p>Every statement below is a whole constant, never a string assembled from a
 * role name or a column list. A test about privilege enforcement that built its own
 * SQL by concatenation would be demonstrating the shape it exists to rule out.
 *
 * <p>The role assertions run under {@code SET LOCAL ROLE}, because this context's
 * datasource connects as the container's owning role. The deployed service assumes
 * the runtime role on every connection instead, through
 * {@code spring.datasource.hikari.connection-init-sql} — which
 * {@link #theDeployedConfigurationMakesTheRuntimeRoleTheApplicationsOwn()} asserts
 * against the deployed file rather than a test copy of it.
 */
@SpringBootTest
@Import({ContainerTestConfiguration.class, InMemorySessionRegistryConfiguration.class})
class AuditAppendOnlyIntegrationTests {

    private static final String USER = "test-user";

    private static final String ADMIN = "test-admin";

    private static final String ASSUME_APPLICATION_ROLE = "SET LOCAL ROLE backend_app";

    private static final String ASSUME_RETENTION_ROLE =
            "SET LOCAL ROLE backend_audit_retention";

    private static final String INSERT_EVENT = """
            INSERT INTO audit_events
                (id, occurred_at, operation, outcome, resource_type, status_class)
                VALUES (?, ?, 'LOGOUT', 'SUCCESS', 'User', 'ok')""";

    private static final String COUNT_EVENT = "SELECT count(*) FROM audit_events WHERE id = ?";

    private static final String COUNT_EVENTS = "SELECT count(*) FROM audit_events";

    private static final String OUTCOME_OF_EVENT =
            "SELECT outcome FROM audit_events WHERE id = ?";

    private static final String UPDATE_EVENT_OUTCOME =
            "UPDATE audit_events SET outcome = 'FAILURE' WHERE id = ?";

    private static final String DELETE_EVENT = "DELETE FROM audit_events WHERE id = ?";

    private static final String DELETE_ALL_EVENTS = "DELETE FROM audit_events";

    private static final String ALL_EVENTS = "SELECT * FROM audit_events";

    private static final String EVENTS_BY_OPERATION =
            "SELECT * FROM audit_events WHERE operation = ?";

    /**
     * The administrative standing flag, read from the one table that now holds it: the
     * {@code accounts} table is gone and {@code active} is the SCIM attribute it collapsed
     * into. Keyed on the normalized {@code userName}, which is the column uniqueness is
     * decided on.
     */
    private static final String CHANGE_REQUIRED_SINCE_OF_USER =
            "SELECT password_change_required_since FROM scim_users WHERE resource_id = ?";

    private static final String FORCED_FAILURE_FUNCTION = """
            CREATE OR REPLACE FUNCTION forced_append_failure() RETURNS trigger
                LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'forced audit append failure';
            END
            $$""";

    private static final String FORCED_FAILURE_TRIGGER = """
            CREATE TRIGGER forced_append_failure
                BEFORE INSERT ON audit_events
                FOR EACH ROW EXECUTE FUNCTION forced_append_failure()""";

    private static final String DROP_FORCED_FAILURE_TRIGGER =
            "DROP TRIGGER IF EXISTS forced_append_failure ON audit_events";

    /** Postgres's code for a privilege the current role does not hold. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private IdentityAdministrationService administration;

    @Autowired
    private AuditRetentionService retention;

    @Autowired
    private AuditTrail auditTrail;

    @Autowired
    private InMemoryAccountSessions sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        restore(USER);
        clearRecordedEvents();
    }

    @AfterEach
    void removeForcedFailure() {
        jdbc.execute(DROP_FORCED_FAILURE_TRIGGER);
        restore(USER);
    }

    // A write whose audit insert cannot commit is rolled back

    @Test
    void aForcedAuditInsertFailureRollsBackTheMutationItWasRecording() {
        ScimUser target = throwaway("append-rollback-target");
        try {
            forceAppendFailure();

            assertThatThrownBy(() -> administration.forcePasswordChange(target.id(), ADMIN))
                    .isInstanceOf(RuntimeException.class);

            // Read back from the database, not from the object the call returned: the
            // claim is that nothing was committed.
            assertThat(jdbc.queryForObject(CHANGE_REQUIRED_SINCE_OF_USER, Timestamp.class,
                    target.id())).isNull();
            assertThat(allEvents()).isEmpty();
            // The revocation is deferred to after the commit, so a rollback never
            // reaches it either.
            assertThat(sessions.revocations()).doesNotContain(target.id());
        } finally {
            jdbc.execute(DROP_FORCED_FAILURE_TRIGGER);
            discard(target);
        }
    }

    /**
     * The same write with no forced failure commits, so the test above is about the
     * rollback rather than about a forced change that never worked in the first place.
     */
    @Test
    void theSameWriteCommitsWhenTheAppendSucceeds() {
        ScimUser target = throwaway("append-commit-target");
        try {
            administration.forcePasswordChange(target.id(), ADMIN);

            assertThat(jdbc.queryForObject(CHANGE_REQUIRED_SINCE_OF_USER, Timestamp.class,
                    target.id())).isNotNull();
            assertThat(rows(AuditOperation.PASSWORD_CHANGE_REQUIRE)).hasSize(1);
        } finally {
            discard(target);
        }
    }

    // A failure event that cannot be appended alerts without changing the response

    @Test
    void aForcedFailureEventAppendFailureAlertsAndLeavesTheResponseUnchanged()
            throws Exception {
        forceAppendFailure();

        try (CapturedLog captured = CapturedLog.attach()) {
            mvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refusedCredentials()))
                    // Still the bare 401 the caller would have received anyway —
                    // not a 500, and not a body that says the trail is broken.
                    .andExpect(status().isUnauthorized())
                    .andExpect(result -> assertThat(
                            result.getResponse().getContentAsString()).isEmpty());

            List<ILoggingEvent> alerts = captured.withAction(
                    Level.ERROR, LogEvent.LOCAL_ACTION, "audit.append");
            assertThat(alerts).hasSize(1);
            Map<String, Object> fields = CapturedLog.fields(alerts.get(0));
            assertThat(fields).containsEntry(LogEvent.OUTCOME, LogEvent.FAILURE);
            assertThat(fields).containsEntry(
                    LogEvent.AUDIT_OPERATION, AuditOperation.LOGIN_FAILURE.name());
            // The alert says which record is missing and names the failure's own
            // type; it carries no value the unwritten event withheld.
            assertThat(fields.get(LogEvent.REASON)).isNotNull();
            assertThat(fields.values().stream().map(String::valueOf))
                    .noneMatch(value -> value.contains(USER));
        }

        assertThat(allEvents()).isEmpty();
        // The mutation a refused login makes — lengthening the failure run — is
        // untouched by the append that failed, because a failure event is fail-open.
        assertThat(require(USER).login().failedLoginAttempts()).isEqualTo(1);
    }

    /**
     * A fail-open append commits even when the transaction that triggered it does not.
     *
     * <p>This is what {@code PROPAGATION_REQUIRES_NEW} on the service's isolated
     * template buys, and it is the whole of fail-open: without it the append joins the
     * caller's transaction, and a caller that rolls back takes the audit row with it —
     * so a refused login inside a rolled-back unit of work would leave no trace at all,
     * silently, with no alert raised because nothing threw. Asserted with a caller
     * transaction marked rollback-only rather than by reading the template's
     * configuration, because the propagation setting is a means and the surviving row
     * is the claim.
     */
    @Test
    void aFailOpenAppendCommitsEvenWhenTheCallersTransactionRollsBack() {
        UUID subjectId = idOf(USER);

        transactions.executeWithoutResult(status -> {
            auditTrail.recordLoginFailure(
                    subjectId, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForList(EVENTS_BY_OPERATION, AuditOperation.LOGIN_FAILURE.name()))
                .as("the failure event outlived the rollback")
                .singleElement()
                .satisfies(row ->
                        assertThat(row.get("subject_id")).hasToString(subjectId.toString()));
    }

    // Role separation, at the database

    @Test
    void theApplicationsRoleMayInsertAndReadAuditRows() {
        Integer read = asRole(ASSUME_APPLICATION_ROLE, () ->
                jdbc.queryForObject(COUNT_EVENTS, Integer.class));
        assertThat(read).isNotNull();

        UUID inserted = UUID.randomUUID();
        asRole(ASSUME_APPLICATION_ROLE, () -> insertEvent(inserted));

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, inserted)).isEqualTo(1);
    }

    @Test
    void theApplicationsRoleMayNotUpdateARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> asRole(
                        ASSUME_APPLICATION_ROLE,
                        () -> jdbc.update(UPDATE_EVENT_OUTCOME, recorded)))
                .satisfies(failure -> assertThat(sqlStates(failure))
                        .contains(INSUFFICIENT_PRIVILEGE))
                .rootCause()
                .hasMessageContaining("audit_events");

        assertThat(jdbc.queryForObject(OUTCOME_OF_EVENT, String.class, recorded))
                .isEqualTo("SUCCESS");
    }

    @Test
    void theApplicationsRoleMayNotDeleteARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> asRole(
                        ASSUME_APPLICATION_ROLE, () -> jdbc.update(DELETE_EVENT, recorded)))
                .satisfies(failure -> assertThat(sqlStates(failure))
                        .contains(INSUFFICIENT_PRIVILEGE));

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isEqualTo(1);
    }

    /**
     * The grants alone say nothing about a connection that arrives as the table's
     * owner — a console session, or a deployment that never set the runtime role.
     * The table's own trigger refuses that one too, so append-only survives a
     * misconfiguration instead of depending on one being absent.
     */
    @Test
    void evenTheOwningRoleIsRefusedByTheTablesOwnGuard() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> jdbc.update(DELETE_EVENT, recorded))
                .rootCause()
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(UPDATE_EVENT_OUTCOME, recorded))
                .rootCause()
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isEqualTo(1);
    }

    @Test
    void theRetentionRoleMayDeleteButNotInsert() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        asRole(ASSUME_RETENTION_ROLE, () -> jdbc.update(DELETE_EVENT, recorded));
        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isZero();

        assertThatThrownBy(() ->
                        asRole(ASSUME_RETENTION_ROLE, () -> insertEvent(UUID.randomUUID())))
                .satisfies(failure -> assertThat(sqlStates(failure))
                        .contains(INSUFFICIENT_PRIVILEGE));
    }

    /**
     * The retention job goes through the role it is reserved for, so the delete the
     * application cannot perform is performed by the job that owns it — driven
     * through the service rather than through SQL this test wrote.
     */
    @Test
    void theRetentionJobRemovesWhatHasAgedOutAndKeepsWhatHasNot() {
        UUID agedOut = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        insertEventAt(agedOut, Instant.now().minus(Duration.ofDays(400)));
        insertEventAt(recent, Instant.now());

        assertThat(retention.deleteAgedOutEvents().deleted()).isEqualTo(1);

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, agedOut)).isZero();
        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recent)).isEqualTo(1);
    }

    /**
     * The deployed configuration, read from the file a deployment actually loads.
     *
     * <p>The test resources' {@code application.yaml} shadows the main one entirely,
     * so an assertion through the classpath or through the {@code Environment} would
     * describe this test context's settings and say nothing about the running
     * service. This reads the deployed document off disk for that reason.
     */
    @Test
    void theDeployedConfigurationMakesTheRuntimeRoleTheApplicationsOwn() throws IOException {
        String deployed = Files.readString(Path.of("src/main/resources/application.yaml"));

        assertThat(deployed).contains("connection-init-sql: SET ROLE backend_app");
        // Flyway needs a connection outside that pool, or the migration that creates
        // the role would run as a role that does not exist yet.
        assertThat(deployed).containsPattern("(?s)flyway:.*user: ");
    }

    // Helpers

    /**
     * Installs a trigger that refuses every insert into the audit table, for the
     * length of one test. Dropped in {@code @AfterEach} whether the test passed or
     * not — a forced failure left behind would poison every test after it.
     */
    private void forceAppendFailure() {
        jdbc.execute(FORCED_FAILURE_FUNCTION);
        jdbc.execute(FORCED_FAILURE_TRIGGER);
    }

    /**
     * Runs {@code work} in a transaction that has assumed a role.
     *
     * <p>{@code assumeRole} is a whole statement from a constant above rather than a
     * role name spliced into one: a role name concatenated into SQL is an injection
     * shape even when today's value is a literal.
     */
    private <T> T asRole(String assumeRole, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.execute(assumeRole);
            return work.get();
        });
    }

    private int insertEvent(UUID id) {
        return insertEventAt(id, Instant.now());
    }

    private int insertEventAt(UUID id, Instant occurredAt) {
        return jdbc.update(INSERT_EVENT, id, Timestamp.from(occurredAt));
    }

    private List<Map<String, Object>> rows(AuditOperation operation) {
        return jdbc.queryForList(EVENTS_BY_OPERATION, operation.name());
    }

    private List<Map<String, Object>> allEvents() {
        return jdbc.queryForList(ALL_EVENTS);
    }

    /** Every SQLSTATE in the failure's cause chain. */
    private static List<String> sqlStates(Throwable failure) {
        List<String> states = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                states.add(sql.getSQLState());
            }
        }
        return states;
    }

    private static String refusedCredentials() {
        return "{\"username\":\"test-user\",\"password\":\"not-the-password\"}";
    }

    private UUID idOf(String userName) {
        return require(userName).id();
    }

    private ScimUser require(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow();
    }

    /** The stored uniqueness form of a userName, which is what the row is keyed on. */

    private void clearRecordedEvents() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute(ASSUME_RETENTION_ROLE);
            jdbc.update(DELETE_ALL_EVENTS);
        });
    }

    /**
     * Returns the User to active, unlocked and with no failure run recorded.
     *
     * <p>Two narrow writes rather than one full-row write, because that is what the port
     * offers: the login state and the {@code active} flag are written separately, and only
     * the second advances the resource's version — a failure run is not a SCIM attribute.
     *
     * <p>Wrapped in a transaction because both are modifying queries: without one they have
     * no {@code EntityManager} to flush.
     */
    /**
     * A credentialed User of this test's own, so a forced change it commits does not leave a
     * seeded identity flagged for every other test sharing this context — a flag only a real
     * password change clears.
     */
    private ScimUser throwaway(String userName) {
        return transactions.execute(status -> users.create(ScimUser.created(
                UUID.randomUUID(),
                new com.example.backend.scim.domain.ScimUserProfile(
                        userName, null, null, null, null, null, true, List.of()),
                "hash",
                Instant.now())));
    }

    private void discard(ScimUser user) {
        transactions.executeWithoutResult(status -> users.deleteById(user.id(), Instant.now()));
        clearRecordedEvents();
    }

    private void restore(String userName) {
        transactions.executeWithoutResult(status -> {
            ScimUser user = require(userName);
            users.updateLoginState(user.id(), ScimLoginState.of(user.login().passwordHash()));
            users.updateActive(user.id(), true, Instant.now());
            // Reactivation requires a password change; the shared seeded baseline is unflagged.
            users.completePasswordChange(user.id(), user.login().passwordHash(), Instant.now());
        });
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}
