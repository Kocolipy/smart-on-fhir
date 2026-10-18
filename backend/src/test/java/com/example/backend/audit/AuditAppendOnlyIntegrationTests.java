package com.example.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.SessionCsrf;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.InMemorySessionRegistryConfiguration;
import com.example.backend.audit.application.AuditLogFields;
import com.example.backend.audit.application.AuditRetentionService;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.SqlFailures;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 *   <li>the app login may insert and read audit rows and may not update or delete one;
 *       the retention login may delete one and may not update it; and the owner login,
 *       which the grants do not bind, is refused both by the table's own guard.
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
 * <p>Each privilege assertion connects as the database login it is about
 * ({@link DatabaseLogins}), on a connection of its own: the server decides by the login,
 * so the claim is only as good as the login it is made through.
 */
@SpringBootTest
@Import({ContainerTestConfiguration.class, InMemorySessionRegistryConfiguration.class})
class AuditAppendOnlyIntegrationTests {

    private static final String USER = "test-user";

    private static final String ADMIN = "test-admin";

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

    private static final String FORCED_FAILURE_TRIGGER = """
            CREATE TRIGGER forced_append_failure
                ON audit_events
                AFTER INSERT
            AS
                THROW 50000, 'forced audit append failure', 1;""";

    private static final String DROP_FORCED_FAILURE_TRIGGER =
            "DROP TRIGGER IF EXISTS forced_append_failure";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimLoginStateRepository loginStates;

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
    private DatabaseLogins logins;

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
        logins.owner().jdbc().execute(DROP_FORCED_FAILURE_TRIGGER);
        restore(USER);
    }

    // A write whose audit insert cannot commit is rolled back

    @Test
    void aForcedAuditInsertFailureRollsBackTheMutationItWasRecording() {
        ScimUser target = throwaway("append-rollback-target");
        try {
            forceAppendFailure();

            assertThatThrownBy(() -> administration.forcePasswordChange(target.id(), idOf(ADMIN)))
                    .isInstanceOf(RuntimeException.class);

            // Read back from the database, not from the object the call returned: the
            // claim is that nothing was committed.
            assertThat(jdbc.queryForObject(CHANGE_REQUIRED_SINCE_OF_USER, OffsetDateTime.class,
                    target.id())).isNull();
            assertThat(allEvents()).isEmpty();
            // The revocation is deferred to after the commit, so a rollback never
            // reaches it either.
            assertThat(sessions.revocations()).doesNotContain(target.id());
        } finally {
            logins.owner().jdbc().execute(DROP_FORCED_FAILURE_TRIGGER);
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
            administration.forcePasswordChange(target.id(), idOf(ADMIN));

            assertThat(jdbc.queryForObject(CHANGE_REQUIRED_SINCE_OF_USER, OffsetDateTime.class,
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
                    AuditLogFields.AUDIT_OPERATION, AuditOperation.LOGIN_FAILURE.name());
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
            auditTrail.append(new Audited.LoginFailure(
                    subjectId, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForList(EVENTS_BY_OPERATION, AuditOperation.LOGIN_FAILURE.name()))
                .as("the failure event outlived the rollback")
                .singleElement()
                .satisfies(row -> assertThat(UUID.fromString(String.valueOf(row.get("subject_id"))))
                        .isEqualTo(subjectId));
    }

    // Role separation, at the database

    @Test
    void theAppLoginMayInsertAndReadAuditRows() {
        JdbcTemplate app = logins.app().jdbc();
        assertThat(app.queryForObject(COUNT_EVENTS, Integer.class)).isNotNull();

        UUID inserted = UUID.randomUUID();
        app.update(INSERT_EVENT, inserted, Instant.now().atOffset(ZoneOffset.UTC));

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, inserted)).isEqualTo(1);
    }

    @Test
    void theAppLoginMayNotUpdateARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> logins.app().jdbc().update(UPDATE_EVENT_OUTCOME, recorded))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.PERMISSION_DENIED.errorCode()))
                .rootCause()
                .hasMessageContaining("The UPDATE permission was denied on the object 'audit_events'");

        assertThat(jdbc.queryForObject(OUTCOME_OF_EVENT, String.class, recorded))
                .isEqualTo("SUCCESS");
    }

    @Test
    void theAppLoginMayNotDeleteARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> logins.app().jdbc().update(DELETE_EVENT, recorded))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.PERMISSION_DENIED.errorCode()))
                .rootCause()
                .hasMessageContaining("The DELETE permission was denied on the object 'audit_events'");

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isEqualTo(1);
    }

    /**
     * The grants say nothing about the owner login, a member of db_owner — a console
     * session, or a migration. The table's own guard refuses it a delete as well as an
     * update, although it could make itself a member of the retention role, so append-only
     * survives a misused credential instead of depending on none being used.
     */
    @Test
    void theOwnerLoginMayNotDeleteARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> logins.owner().jdbc().update(DELETE_EVENT, recorded))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.AUDIT_TRIGGER_REFUSAL.errorCode()))
                .rootCause()
                .hasMessage("audit_events is append-only: backend_owner may not DELETE"
                        + " a recorded event");

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isEqualTo(1);
    }

    @Test
    void theOwnerLoginMayNotUpdateARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> logins.owner().jdbc().update(UPDATE_EVENT_OUTCOME, recorded))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.AUDIT_TRIGGER_REFUSAL.errorCode()))
                .rootCause()
                .hasMessage("audit_events is append-only: backend_owner may not UPDATE"
                        + " a recorded event");

        assertThat(jdbc.queryForObject(OUTCOME_OF_EVENT, String.class, recorded))
                .isEqualTo("SUCCESS");
    }

    /**
     * An UPDATE is told from a DELETE by the columns it sets, not by the rows it found, so
     * one that matches nothing is refused as an UPDATE all the same.
     */
    @Test
    void theOwnerLoginMayNotUpdateEvenWhereNoEventMatches() {
        assertThatThrownBy(() -> logins.owner().jdbc().update(UPDATE_EVENT_OUTCOME,
                        UUID.randomUUID()))
                .rootCause()
                .hasMessage("audit_events is append-only: backend_owner may not UPDATE"
                        + " a recorded event");
    }

    /**
     * A sysadmin enters the database as its owner, and is refused by the guard as the owner
     * is: no principal of the server may delete an audit row but the retention login.
     */
    @Test
    void theServersAdministratorMayNotDeleteARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> logins.superuser().jdbc().update(DELETE_EVENT, recorded))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.AUDIT_TRIGGER_REFUSAL.errorCode()));

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isEqualTo(1);
    }

    @Test
    void theRetentionLoginMayDeleteARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        logins.retention().jdbc().update(DELETE_EVENT, recorded);

        assertThat(jdbc.queryForObject(COUNT_EVENT, Integer.class, recorded)).isZero();
    }

    /** Retention removes what has aged out and changes nothing: its UPDATE grant is gone. */
    @Test
    void theRetentionLoginMayNotUpdateARecordedEvent() {
        UUID recorded = UUID.randomUUID();
        insertEvent(recorded);

        assertThatThrownBy(() -> logins.retention().jdbc().update(UPDATE_EVENT_OUTCOME, recorded))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.PERMISSION_DENIED.errorCode()))
                .rootCause()
                .hasMessageContaining("The UPDATE permission was denied on the object 'audit_events'");

        assertThat(jdbc.queryForObject(OUTCOME_OF_EVENT, String.class, recorded))
                .isEqualTo("SUCCESS");
    }

    @Test
    void theRetentionLoginMayNotInsertAnEvent() {
        assertThatThrownBy(() -> logins.retention().jdbc().update(
                        INSERT_EVENT, UUID.randomUUID(), Instant.now().atOffset(ZoneOffset.UTC)))
                .satisfies(failure -> assertThat(errorCodes(failure))
                        .contains(SqlFailures.PERMISSION_DENIED.errorCode()));
    }

    /**
     * The retention job deletes through the retention login it is reserved for, so the
     * delete the app login cannot perform is performed by the job that owns it — driven
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

    // Helpers

    /**
     * Installs a trigger that refuses every insert into the audit table, for the
     * length of one test, as the owner login — the only one that may change the
     * table. Dropped in {@code @AfterEach} whether the test passed or not — a forced
     * failure left behind would poison every test after it.
     */
    private void forceAppendFailure() {
        logins.owner().jdbc().execute(FORCED_FAILURE_TRIGGER);
    }

    private int insertEvent(UUID id) {
        return insertEventAt(id, Instant.now());
    }

    private int insertEventAt(UUID id, Instant occurredAt) {
        return jdbc.update(INSERT_EVENT, id, occurredAt.atOffset(ZoneOffset.UTC));
    }

    private List<Map<String, Object>> rows(AuditOperation operation) {
        return jdbc.queryForList(EVENTS_BY_OPERATION, operation.name());
    }

    private List<Map<String, Object>> allEvents() {
        return jdbc.queryForList(ALL_EVENTS);
    }

    /** Every SQL Server error number in the failure's cause chain. */
    private static List<Integer> errorCodes(Throwable failure) {
        List<Integer> codes = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                codes.add(sql.getErrorCode());
            }
        }
        return codes;
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

    /** As the retention login, the one principal the table lets delete. */
    private void clearRecordedEvents() {
        logins.retention().jdbc().update(DELETE_ALL_EVENTS);
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
            ScimIdentities.storeFailureRun(
                    loginStates, user.id(), ScimLoginState.of(user.login().passwordHash()));
            // Arranged through the store, which keeps what it is given: active again, and with
            // the shared seeded baseline's unflagged credential and unmoved dormancy basis.
            users.replace(ScimIdentities.withActive(user, true, new ScimLoginState(
                    user.login().passwordHash(), 0, null, null,
                    user.login().lastAuthenticatedAt(), null)), Instant.now());
        });
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}
