package com.example.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;

import com.example.backend.SessionCsrf;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.InMemorySessionRegistryConfiguration;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every audit-worthy thing this application already does, driven through the real
 * filter chain against a real Postgres, with the rows it produced read back out of
 * the database.
 *
 * <p>Driven end to end rather than against the services because the claim is about
 * what the application produces <em>today</em>: a service-level test would assert
 * that a use case called the trail, which is a different and weaker statement than
 * that a request arriving at this service leaves exactly one correctly-shaped row
 * behind. The rows are read with SQL rather than through the repository for the
 * same reason — what is asserted is the bytes that landed, not a mapping's opinion
 * of them.
 */
@SpringBootTest
@Import({ContainerTestConfiguration.class, InMemorySessionRegistryConfiguration.class})
class AuditEventRecordingIntegrationTests {

    private static final String USER = "test-user";

    private static final String USER_PASSWORD = "test-password";

    private static final String ADMIN = "test-admin";

    private static final String EVENTS_BY_OPERATION =
            "SELECT * FROM audit_events WHERE operation = ? ORDER BY occurred_at";

    private static final String ALL_EVENTS = "SELECT * FROM audit_events";

    private static final String AUDIT_COLUMNS =
            "SELECT column_name FROM information_schema.columns"
            + " WHERE table_name = 'audit_events'";

    private static final String DELETE_ALL_EVENTS = "DELETE FROM audit_events";

    private static final String ASSUME_RETENTION_ROLE =
            "SET LOCAL ROLE backend_audit_retention";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private InMemoryAccountSessions accountSessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    /**
     * The filter that mints the correlation id an audit event records. Added to the
     * chain because without it the recorded {@code request_id} would be null and the
     * assertion that it is present would be asserting the harness rather than the
     * service.
     */
    @Autowired
    private RequestIdFilter requestIdFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        clearRecordedEvents();
        restore(USER);
        restore(ADMIN);
        clearRecordedEvents();
    }

    // One event per flow, correctly shaped

    @Test
    void anAcceptedLoginProducesExactlyOneEvent() throws Exception {
        logIn(USER, USER_PASSWORD).andExpect(status().isOk());

        Map<String, Object> event = only(AuditOperation.LOGIN_SUCCESS);
        assertThat(event).containsEntry("outcome", "SUCCESS");
        assertThat(event).containsEntry("actor_id", idOf(USER));
        assertThat(event).containsEntry("subject_id", idOf(USER));
        assertThat(event).containsEntry("resource_type", "User");
        assertThat(event).containsEntry("status_class", "ok");
        assertThat(event).containsEntry("http_method", "POST");
        assertThat(event).containsEntry("http_path", "/api/auth/login");
        assertThat(event.get("request_id")).isNotNull();
        assertThat(event.get("occurred_at")).isNotNull();
    }

    /** D15: password Login records method {@code password}, accepted or refused. */
    @Test
    void passwordLoginRecordsMethodPasswordOnSuccessAndOnFailure() throws Exception {
        logIn(USER, "not-the-password").andExpect(status().isUnauthorized());
        logIn(USER, USER_PASSWORD).andExpect(status().isOk());

        assertThat(List.of(only(AuditOperation.LOGIN_FAILURE), only(AuditOperation.LOGIN_SUCCESS)))
                .allSatisfy(event -> assertThat(event).containsEntry("login_method", "password"));
    }

    @Test
    void aRefusedLoginProducesExactlyOneEventNamingTheReason() throws Exception {
        logIn(USER, "not-the-password").andExpect(status().isUnauthorized());

        Map<String, Object> event = only(AuditOperation.LOGIN_FAILURE);
        assertThat(event).containsEntry("outcome", "FAILURE");
        assertThat(event).containsEntry("actor_id", null);
        assertThat(event).containsEntry("subject_id", idOf(USER));
        assertThat(event).containsEntry("status_class", "client_error");
        assertThat(event).containsEntry(
                "error_code", AuditRefusalReason.BAD_CREDENTIALS.name());
        assertThat(event).containsEntry("changed_paths", "failedLoginAttempts");
    }

    /**
     * The submitted username names no account, so there is no subject — and the
     * submitted value is not recorded in its place, which is the whole point.
     */
    @Test
    void aRefusedLoginAgainstAnUnknownNameRecordsNoSubject() throws Exception {
        logIn("nobody-by-that-name", "whatever").andExpect(status().isUnauthorized());

        Map<String, Object> event = only(AuditOperation.LOGIN_FAILURE);
        assertThat(event).containsEntry("subject_id", null);
        assertThat(event).containsEntry(
                "error_code", AuditRefusalReason.UNKNOWN_ACCOUNT.name());
    }

    @Test
    void aLogoutProducesExactlyOneEvent() throws Exception {
        MockHttpSession session = (MockHttpSession) logIn(USER, USER_PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession();
        clearRecordedEvents();

        mvc.perform(withCsrf(delete("/api/auth/logout")).session(session))
                .andExpect(status().isNoContent());

        Map<String, Object> event = only(AuditOperation.LOGOUT);
        assertThat(event).containsEntry("actor_id", idOf(USER));
        assertThat(event).containsEntry("subject_id", idOf(USER));
        assertThat(event).containsEntry("http_method", "DELETE");
        assertThat(event).containsEntry("http_path", "/api/auth/logout");
    }

    /**
     * The fifth consecutive refusal is what locks the account, so the lockout is
     * recorded once and not on the four attempts before it or on any attempt made
     * during the window it opened.
     */
    @Test
    void reachingTheFailureLimitProducesExactlyOneLockoutEvent() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            logIn(USER, "not-the-password").andExpect(status().isUnauthorized());
        }
        // A sixth attempt, refused by the lockout rather than by the password,
        // neither extends the window nor records a second lockout.
        logIn(USER, USER_PASSWORD).andExpect(status().isUnauthorized());

        Map<String, Object> event = only(AuditOperation.LOCKOUT_SET);
        assertThat(event).containsEntry("subject_id", idOf(USER));
        assertThat(event).containsEntry("actor_id", null);
        assertThat(event).containsEntry(
                "changed_paths", "failedLoginAttempts,lockedAt,lockCause");
        assertThat(rows(AuditOperation.LOGIN_FAILURE)).hasSize(6);
        assertThat(rows(AuditOperation.LOGIN_FAILURE))
                .extracting(row -> row.get("error_code"))
                .contains(AuditRefusalReason.ACCOUNT_LOCKED.name());
    }

    /**
     * The lockout's Session revocation is audited under its cause, after the commit — and,
     * the account holding no other session, only because it ended one.
     */
    @Test
    void reachingTheFailureLimitAuditsTheRevocationUnderTheFailureRunLockout() throws Exception {
        accountSessions.open(idOf(USER), "held-elsewhere");

        for (int attempt = 0; attempt < 5; attempt++) {
            logIn(USER, "not-the-password").andExpect(status().isUnauthorized());
        }

        Map<String, Object> event = only(AuditOperation.USER_SESSIONS_REVOKE);
        assertThat(List.of(event.get("outcome"), event.get("error_code"), event.get("subject_id")))
                .isEqualTo(List.of("SUCCESS", "FAILURE_RUN_LOCKOUT", idOf(USER)));
    }

    /**
     * A rejected Login is a refusal (ADR 0004): when the lockout it imposes cannot end the
     * account's sessions because the store is down, the answer is still the bare {@code 401},
     * and the failure is on record and alerted rather than silently lost.
     */
    @Test
    void aLockoutWhoseRevocationFailsStillAnswersTheBareRefusalWithTheFailureAuditedAndAlerted()
            throws Exception {
        accountSessions.failWith(new IllegalStateException("session store unavailable"));
        try (CapturedLog logs = CapturedLog.attach()) {
            for (int attempt = 0; attempt < 4; attempt++) {
                logIn(USER, "not-the-password").andExpect(status().isUnauthorized());
            }

            logIn(USER, "not-the-password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));

            Map<String, Object> event = only(AuditOperation.USER_SESSIONS_REVOKE);
            assertThat(List.of(event.get("outcome"), event.get("error_code")))
                    .isEqualTo(List.of("FAILURE", "FAILURE_RUN_LOCKOUT"));
            assertThat(logs.withAction(Level.ERROR, LogEvent.ACTION, "session-end"))
                    .as("the revocation failure is alerted").hasSize(1);
        } finally {
            accountSessions.failWith(null);
        }
    }

    /**
     * A lockout imposed long ago is still in force, and no login attempt against it
     * records a lift — there is no unrequested lift left to record, so the only
     * {@code LOCKOUT_LIFT} row any flow can produce is an administrator's unlock.
     */
    @Test
    void aLockoutStandingSinceLongAgoProducesNoLiftAtTheNextAttempt() throws Exception {
        ScimUser user = require(USER);
        transactions.executeWithoutResult(status -> users.updateLoginState(
                user.id(),
                new ScimLoginState(
                        user.login().passwordHash(), 5, Instant.now().minusSeconds(3600))));
        clearRecordedEvents();

        logIn(USER, USER_PASSWORD).andExpect(status().isUnauthorized());

        assertThat(rows(AuditOperation.LOCKOUT_LIFT)).isEmpty();
        assertThat(require(USER).login().isLocked()).isTrue();
    }

    @Test
    void unlockingAnAccountProducesExactlyOneLiftEventNamingTheAdministrator()
            throws Exception {
        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", idOf(USER)))
                        .session(sessionOf(ADMIN)))
                .andExpect(status().isOk());

        Map<String, Object> event = only(AuditOperation.LOCKOUT_LIFT);
        assertThat(event).containsEntry("error_code", null);
        assertThat(event).containsEntry("actor_id", idOf(ADMIN));
        assertThat(event).containsEntry("subject_id", idOf(USER));
        // The route template, which names the stable id's slot and never the value.
        assertThat(event).containsEntry("http_path", "/api/admin/accounts/{id}/unlock");
    }

    // Redaction

    /**
     * Every flow at once, then a search of every text column in every recorded row
     * for the values that must never be in one.
     *
     * <p>A search for absence proves nothing unless the thing searched was
     * populated, so the same scan is first asked for a value that <em>is</em> there
     * — the resource type — and has to find it. Without that check this test would
     * pass against an empty table.
     */
    @Test
    void noRecordedEventBodyCarriesAUsernameOrAPassword() throws Exception {
        logIn(USER, USER_PASSWORD).andExpect(status().isOk());
        logIn(USER, "wrong-" + USER_PASSWORD).andExpect(status().isUnauthorized());
        logIn("no-such-account", "irrelevant").andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", idOf(USER)))
                        .session(sessionOf(ADMIN)))
                .andExpect(status().isOk());

        assertThat(allEvents()).hasSizeGreaterThanOrEqualTo(3);
        // The scan works: a value that is present is found.
        assertThat(rowsContaining("User")).isNotZero();

        assertThat(rowsContaining(USER)).isZero();
        assertThat(rowsContaining(ADMIN)).isZero();
        assertThat(rowsContaining(USER_PASSWORD)).isZero();
        assertThat(rowsContaining("no-such-account")).isZero();
        assertThat(rowsContaining("$argon2")).isZero();
        assertThat(rowsContaining("Bearer")).isZero();
    }

    /**
     * The table has no column a readable identifier could be written into in the
     * first place. Asserted against the deployed schema rather than the entity, so
     * a column added by a migration without a mapping is caught too.
     */
    @Test
    void theAuditTableHasNoColumnThatCouldHoldACredential() {
        List<String> columns = jdbc.queryForList(AUDIT_COLUMNS, String.class);

        assertThat(columns).isNotEmpty();
        assertThat(columns).noneSatisfy(column -> assertThat(column).containsAnyOf(
                "username", "user_name", "password", "secret", "token", "bearer",
                "credential", "cookie", "hash"));
        assertThat(columns).contains("actor_id", "subject_id", "resource_id");
    }

    // Authorization refusals

    /**
     * A signed-in User refused an operation for want of its Permission leaves exactly one
     * {@code ACCESS_DENIED} event: the caller as actor and subject, the operation as its method and
     * route TEMPLATE, and the one generic reason. A search of the row for the Permission's name,
     * a Role's and the policy's vocabulary finds none of them — after first finding the reason,
     * so the search is proven to read the row it searched.
     */
    @Test
    void anAuthorizationRefusalIsAuditedWithTheCallerTheOperationAndAGenericReason()
            throws Exception {
        MockHttpSession session = (MockHttpSession) logIn(USER, USER_PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession();
        clearRecordedEvents();

        mvc.perform(get("/api/admin/audit-events").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", idOf(ADMIN)))
                        .session(session))
                .andExpect(status().isForbidden());

        List<Map<String, Object>> refusals = rows(AuditOperation.ACCESS_DENIED);
        assertThat(refusals).hasSize(2);
        Map<String, Object> read = refusals.get(0);
        assertThat(read).containsEntry("outcome", "FAILURE");
        assertThat(read).containsEntry("actor_id", idOf(USER));
        assertThat(read).containsEntry("subject_id", idOf(USER));
        assertThat(read).containsEntry("status_class", "client_error");
        assertThat(read).containsEntry("error_code", "INSUFFICIENT_PERMISSIONS");
        assertThat(read).containsEntry("http_method", "GET");
        assertThat(read).containsEntry("http_path", "/api/admin/audit-events");
        assertThat(read.get("request_id")).isNotNull();
        // The template, never the resolved path, which would carry the target's id.
        assertThat(refusals.get(1)).containsEntry("http_method", "POST");
        assertThat(refusals.get(1))
                .containsEntry("http_path", "/api/admin/accounts/{id}/unlock");

        assertThat(rowsContaining("INSUFFICIENT_PERMISSIONS")).isEqualTo(2);
        for (String disclosed : List.of(
                "audit:read", "user:write", "ROLE_", "Superuser", "Account admin", "Auditor",
                "hasAuthority", "permission:")) {
            assertThat(rowsContaining(disclosed)).as("a row naming %s", disclosed).isZero();
        }
    }

    /** A CSRF refusal says nothing about what the caller may do, so it is not audited. */
    @Test
    void aCsrfRefusalIsNotAnAuditedAuthorizationRefusal() throws Exception {
        MockHttpSession session = (MockHttpSession) logIn(USER, USER_PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession();
        clearRecordedEvents();

        mvc.perform(post("/api/admin/accounts/{id}/unlock", idOf(ADMIN)).session(session))
                .andExpect(status().isForbidden());

        assertThat(rows(AuditOperation.ACCESS_DENIED)).isEmpty();
    }

    // Helpers

    private org.springframework.test.web.servlet.ResultActions logIn(
            String username, String password) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\"}"));
    }

    private Map<String, Object> only(AuditOperation operation) {
        List<Map<String, Object>> found = rows(operation);
        assertThat(found).as("exactly one %s event", operation).hasSize(1);
        return found.get(0);
    }

    private List<Map<String, Object>> rows(AuditOperation operation) {
        return jdbc.queryForList(EVENTS_BY_OPERATION, operation.name());
    }

    private List<Map<String, Object>> allEvents() {
        return jdbc.queryForList(ALL_EVENTS);
    }

    /**
     * How many recorded rows carry {@code value} in any textual field.
     *
     * <p>Scanned in Java over every row the table holds rather than with a query
     * built from the column list: a search for absence has to cover columns nobody
     * remembered to name, and assembling SQL from catalog output to do it would be
     * an injection shape in a test for a redaction rule.
     */
    private long rowsContaining(String value) {
        return allEvents().stream()
                .filter(row -> row.values().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .anyMatch(text -> text.contains(value)))
                .count();
    }

    private UUID idOf(String userName) {
        return require(userName).id();
    }

    private ScimUser require(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow();
    }

    /**
     * Removes recorded events between tests, through the retention role — the
     * application's own role cannot, which is the arrangement
     * {@code AuditAppendOnlyIntegrationTests} proves.
     */
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
     * offers: the login state and the {@code active} flag are written separately, and only the
     * second advances the resource's version — a failure run is not a SCIM attribute.
     *
     * <p>Wrapped in a transaction because both are modifying queries: without one they have no
     * {@code EntityManager} to flush.
     */
    private void restore(String userName) {
        transactions.executeWithoutResult(status -> {
            ScimUser user = require(userName);
            users.updateLoginState(
                    user.id(), ScimLoginState.of(user.login().passwordHash()));
            users.updateActive(user.id(), true, Instant.now());
            // Reactivation requires a password change; the shared seeded baseline is unflagged.
            users.completePasswordChange(user.id(), user.login().passwordHash(), Instant.now());
        });
    }

    private MockHttpSession sessionOf(String username) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                new TestingAuthenticationToken(
                        username, null, TestRoleMappings.SUPERUSER_AUTHORITIES));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);
        return session;
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}
