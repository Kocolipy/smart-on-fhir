package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.SessionCsrf;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.application.DormancyRun;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.auth.application.PasswordChangeService;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimConditionalWrites;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.ScimPasswordHistoryRepository;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket's three end-to-end paths, over real HTTP through the real filter chain, against a real
 * Postgres and a real, indexed Redis session store — no in-memory session registry, so "every other
 * session is gone" is observed as a cookie that no longer authenticates rather than as a call a fake
 * recorded.
 *
 * <ol>
 *   <li>A connector sets a password; the next login is confined to the change flow, every other
 *       route refused — an administrative one included, for a flagged Admin; a valid change; full
 *       access after a fresh login, every earlier session gone.
 *   <li>The Admin-forced equivalent, and Unlock requiring a change.
 *   <li>The dormancy basis under a simulated clock: confined logins do not move it, the completed
 *       change does.
 *   <li>One password acceptance behind both entry paths: reuse refused across them against one
 *       history, and an accepted password remembered only by a write that commits.
 * </ol>
 *
 * <p>Sessions travel as the session cookie between requests, exactly as a browser holds them: the
 * Spring Session filter is in the MockMvc chain, so each request's session is read from and written
 * to Redis.
 */
@SpringBootTest
@Import({ContainerTestConfiguration.class, DormancyTestClockConfiguration.class})
class PasswordChangeLifecycleIntegrationTests {

    private static final String BASE = "/scim/v2";
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    private static final String BOOTSTRAP_ADMIN = "test-admin";
    private static final String BOOTSTRAP_PASSWORD = "test-admin-password";

    private static final String CONNECTOR_PASSWORD = "connector-chosen-1";
    private static final String NEW_PASSWORD = "self-chosen-passphrase";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private DormancyService dormancy;

    @Autowired
    private MutableClock clock;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    @Value("${app.auth.lockout.max-attempts}")
    private int maxAttempts;

    private MockMvc mvc;

    private String writeToken;

    private UUID connectorId;

    @Autowired
    private ScimUserService scimUsers;

    @Autowired
    private PasswordChangeService passwordChanges;

    @Autowired
    private ScimPasswordHistoryRepository passwordHistory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create("lifecycle-connector", BOOTSTRAP_ADMIN).id();
        this.connectorId = connectorId;
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, BOOTSTRAP_ADMIN, TokenPermissions.ALL)
                .presentedValue();
    }

    @AfterEach
    void removeWhatThisTestCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
        jdbc.update("UPDATE scim_users SET password_change_required_since = NULL"
                + " WHERE resource_id = (SELECT id FROM scim_resources WHERE reserved_name IS NOT NULL"
                + " AND resource_type = 'User')");
    }

    // ---- 1. connector-set password ----------------------------------------------------------

    @Test
    void aConnectorSetPasswordConfinesTheNextLoginUntilAValidChangeThenGrantsFullAccess()
            throws Exception {
        String userId = provision("lifecycle-connector-admin", CONNECTOR_PASSWORD);
        addToAdminGroup(userId);
        long versionBefore = scimVersion(userId);

        // A second login ends the first: one concurrent session per User.
        Cookie earlier = logIn("lifecycle-connector-admin", CONNECTOR_PASSWORD, null, true);
        Cookie confined = logIn("lifecycle-connector-admin", CONNECTOR_PASSWORD, null, true);
        assertThat(status(get("/api/auth/me"), earlier)).isEqualTo(401);

        // A second live session planted straight in the store, so "every session of that User"
        // is more than the one that submits the change.
        String other = openSessionFor(userId);

        assertConfined(confined);

        // Wrong current password: 401, counted toward the Login lockout.
        assertThat(changePassword(confined, "not-the-current-one", NEW_PASSWORD)
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(failedAttempts(userId)).isEqualTo(1);

        // Policy violation and reuse: 400 naming the rule, echoing neither value.
        MvcResult tooShort = changePassword(confined, CONNECTOR_PASSWORD, "short-one");
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        String tooShortBody = tooShort.getResponse().getContentAsString();
        assertThat(json.readTree(tooShortBody).get("rule").asText()).isEqualTo("TOO_SHORT");
        assertThat(tooShortBody).doesNotContain("short-one").doesNotContain(CONNECTOR_PASSWORD);

        MvcResult reused = changePassword(confined, CONNECTOR_PASSWORD, CONNECTOR_PASSWORD);
        assertThat(reused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(reused.getResponse().getContentAsString()).get("rule").asText())
                .isEqualTo("REUSED");
        assertThat(reused.getResponse().getContentAsString()).doesNotContain(CONNECTOR_PASSWORD);

        // The valid change.
        assertThat(changePassword(confined, CONNECTOR_PASSWORD, NEW_PASSWORD)
                .getResponse().getStatus()).isEqualTo(204);

        // Every session is gone, the submitter's included.
        assertThat(status(get("/api/auth/me"), confined)).isEqualTo(401);
        assertThat(sessionRepository.findById(other)).isNull();
        assertThat(sessionRepository.findByPrincipalName(userId)).isEmpty();

        // The stored state: flag cleared, version advanced, a redacted event.
        assertThat(jdbc.queryForObject(
                "SELECT password_change_required_since FROM scim_users WHERE resource_id = ?::uuid",
                Timestamp.class, userId)).isNull();
        assertThat(scimVersion(userId)).isEqualTo(versionBefore + 1);
        assertThat(failedAttempts(userId)).isZero();
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT * FROM audit_events WHERE subject_id = ?::uuid AND operation = 'PASSWORD_CHANGE'"
                        + " AND outcome = 'SUCCESS'", userId);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.get("actor_id").toString()).isEqualTo(userId);
            assertThat(event.get("changed_paths").toString()).contains("password");
            assertThat(event.values()).allSatisfy(value -> assertThat(String.valueOf(value))
                    .doesNotContain(NEW_PASSWORD)
                    .doesNotContain(CONNECTOR_PASSWORD));
        });

        // The old password no longer works; a fresh login with the new one has full access.
        assertThat(logInStatus("lifecycle-connector-admin", CONNECTOR_PASSWORD)).isEqualTo(401);
        Cookie fresh = logIn("lifecycle-connector-admin", NEW_PASSWORD, "ADMIN", false);
        assertThat(status(get("/api/admin/accounts"), fresh)).isEqualTo(200);
        assertThat(status(get("/api/count"), fresh)).isEqualTo(200);
    }

    // ---- 2. Admin-forced change, and Unlock ------------------------------------------------

    @Test
    void anAdminForcedChangeEndsTheUsersSessionsAndConfinesItsNextLoginUntilItChanges()
            throws Exception {
        String forcedId = provisionAndSettle("lifecycle-forced", CONNECTOR_PASSWORD, NEW_PASSWORD);
        Cookie before = logIn("lifecycle-forced", NEW_PASSWORD, "USER", false);
        assertThat(status(get("/api/self"), before)).isEqualTo(200);

        Cookie admin = logIn(BOOTSTRAP_ADMIN, BOOTSTRAP_PASSWORD, "ADMIN", false);
        MvcResult forced = send(post("/api/admin/accounts/{id}/force-password-change", forcedId),
                admin);
        assertThat(forced.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(forced.getResponse().getContentAsString())
                .get("passwordChangeRequired").booleanValue()).isTrue();

        assertThat(status(get("/api/auth/me"), before))
                .as("forcing a change ends the sessions the User already holds")
                .isEqualTo(401);

        Cookie confined = logIn("lifecycle-forced", NEW_PASSWORD, null, true);
        assertConfined(confined);
        String second = "another-self-chosen-one";
        assertThat(changePassword(confined, NEW_PASSWORD, second).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(status(get("/api/auth/me"), confined)).isEqualTo(401);

        Cookie fresh = logIn("lifecycle-forced", second, "USER", false);
        // Baseline access back: self-service answers again (this User holds no Permission).
        assertThat(status(get("/api/self"), fresh)).isEqualTo(200);
    }

    @Test
    void theAdminForcedChangeIsRefusedOnTheCallersOwnAccountAndOnTheBootstrapAdmin()
            throws Exception {
        String userId = provisionAndSettle("lifecycle-other-admin", CONNECTOR_PASSWORD, NEW_PASSWORD);
        addToAdminGroup(userId);
        Cookie otherAdmin = logIn("lifecycle-other-admin", NEW_PASSWORD, "ADMIN", false);

        assertThat(status(post("/api/admin/accounts/{id}/force-password-change", userId),
                otherAdmin)).isEqualTo(403);
        assertThat(status(post("/api/admin/accounts/{id}/force-password-change",
                idOf(BOOTSTRAP_ADMIN)), otherAdmin)).isEqualTo(403);
    }

    /**
     * IM8 as-15 / ac-6: Unlock requires a change, because the credential that reached the threshold
     * may be the one being guessed. After Unlock the User authenticates and is confined.
     */
    @Test
    void anUnlockedUserAuthenticatesIntoTheChangeFlow() throws Exception {
        String unlockedId =
                provisionAndSettle("lifecycle-unlocked", CONNECTOR_PASSWORD, NEW_PASSWORD);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            assertThat(logInStatus("lifecycle-unlocked", "wrong-guess-" + attempt)).isEqualTo(401);
        }
        assertThat(logInStatus("lifecycle-unlocked", NEW_PASSWORD))
                .as("locked: the correct password is refused")
                .isEqualTo(401);

        Cookie admin = logIn(BOOTSTRAP_ADMIN, BOOTSTRAP_PASSWORD, "ADMIN", false);
        MvcResult unlocked = send(post("/api/admin/accounts/{id}/unlock", unlockedId), admin);
        assertThat(unlocked.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(unlocked.getResponse().getContentAsString())
                .get("passwordChangeRequired").booleanValue()).isTrue();

        Cookie confined = logIn("lifecycle-unlocked", NEW_PASSWORD, null, true);
        assertConfined(confined);
    }

    /** The change counts toward the same lockout; at the threshold both paths are blocked. */
    @Test
    void wrongCurrentPasswordsLockTheUserOutOfBothLoginAndTheChangeFlow() throws Exception {
        String userId = provision("lifecycle-guessed", CONNECTOR_PASSWORD);
        Cookie confined = logIn("lifecycle-guessed", CONNECTOR_PASSWORD, null, true);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            assertThat(changePassword(confined, "wrong-guess-" + attempt, NEW_PASSWORD)
                    .getResponse().getStatus()).isEqualTo(401);
        }

        assertThat(jdbc.queryForObject(
                "SELECT locked_at IS NOT NULL FROM scim_users WHERE resource_id = ?::uuid",
                Boolean.class, userId)).isTrue();
        assertThat(status(get("/api/auth/me"), confined))
                .as("imposing the lock ended every session")
                .isEqualTo(401);
        assertThat(logInStatus("lifecycle-guessed", CONNECTOR_PASSWORD)).isEqualTo(401);
    }

    // ---- 3. the dormancy basis ---------------------------------------------------------------

    /**
     * A login confined by a required change is not use of the account, so it does not move the
     * dormancy basis: a User that keeps logging in with an imposed credential and never replaces
     * it is still locked for dormancy once the lockout window has passed since it was created.
     *
     * <p>The User's creation is backdated rather than the clock advanced: the job measures every
     * User in the database against the clock, and a session opened after an advance would already
     * be past its absolute lifetime.
     */
    @Test
    void confinedLoginsDoNotMoveTheDormancyBasisSoAnUnchangedCredentialIsStillLocked()
            throws Exception {
        String userId = provision("lifecycle-lapsed", CONNECTOR_PASSWORD);
        backdateCreation(userId, DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.plusDays(1));
        logIn("lifecycle-lapsed", CONNECTOR_PASSWORD, null, true);
        logIn("lifecycle-lapsed", CONNECTOR_PASSWORD, null, true);
        assertThat(lastAuthenticatedAt(userId))
                .as("a confined login leaves the dormancy basis where it was")
                .isNull();
        assertThat(sessionRepository.findByPrincipalName(userId)).isNotEmpty();

        DormancyRun run = dormancy.run();

        assertThat(run.locked()).contains(UUID.fromString(userId));
        assertThat(jdbc.queryForObject(
                "SELECT lock_cause FROM scim_users WHERE resource_id = ?::uuid",
                String.class, userId)).isEqualTo("DORMANCY");
        assertThat(jdbc.queryForObject(
                "SELECT active FROM scim_users WHERE resource_id = ?::uuid",
                Boolean.class, userId)).as("active is never written").isTrue();
        assertThat(sessionRepository.findByPrincipalName(userId))
                .as("the locked User's confined sessions are revoked")
                .isEmpty();
    }

    /**
     * The completed change is the first use of the account, so it moves the dormancy basis: a User
     * that changed its password is not locked by the window that runs from its creation, and an
     * unconfined login afterwards moves the basis as before.
     */
    @Test
    void theCompletedChangeMovesTheDormancyBasis() throws Exception {
        String userId = provision("lifecycle-settled", CONNECTOR_PASSWORD);
        backdateCreation(userId, DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.plusDays(1));
        Cookie confined = logIn("lifecycle-settled", CONNECTOR_PASSWORD, null, true);
        assertThat(changePassword(confined, CONNECTOR_PASSWORD, NEW_PASSWORD)
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(lastAuthenticatedAt(userId)).isEqualTo(Timestamp.from(clock.instant()));

        assertThat(dormancy.run().locked())
                .as("91 days since creation, but none since the change")
                .doesNotContain(UUID.fromString(userId));
        assertThat(jdbc.queryForObject(
                "SELECT locked_at IS NULL FROM scim_users WHERE resource_id = ?::uuid",
                Boolean.class, userId)).isTrue();

        jdbc.update("UPDATE scim_users SET last_authenticated_at = ? WHERE resource_id = ?::uuid",
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))), userId);
        logIn("lifecycle-settled", NEW_PASSWORD, "USER", false);
        assertThat(lastAuthenticatedAt(userId))
                .as("an unconfined login moves the basis as before")
                .isEqualTo(Timestamp.from(clock.instant()));
    }

    private void backdateCreation(String userId, Duration ago) {
        jdbc.update("UPDATE scim_resources SET created_at = ? WHERE id = ?::uuid",
                Timestamp.from(clock.instant().minus(ago)), userId);
    }

    // ---- 4. one password acceptance behind both entry paths ---------------------------------

    /**
     * The ticket's demonstration: a connector-set password, replaced by the User, then refused reuse
     * through both entry paths — the connector's of the password the User chose, the User's of the
     * passwords the connector set — against one history, with the flag, audit and session outcome
     * each path owns.
     */
    @Test
    void theConnectorAndTheSelfServiceChangeShareOnePasswordAcceptanceAndHistory() throws Exception {
        String second = "connector-second-2";
        String userId = provision("lifecycle-shared", CONNECTOR_PASSWORD);
        assertThat(historyOf(userId)).containsExactly(storedHash(userId));
        assertThat(changeRequired(userId)).isTrue();

        // Self-service replacement: flag cleared, remembered once, every session ended.
        Cookie confined = logIn("lifecycle-shared", CONNECTOR_PASSWORD, null, true);
        assertThat(changePassword(confined, CONNECTOR_PASSWORD, NEW_PASSWORD)
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(changeRequired(userId)).isFalse();
        assertThat(historyOf(userId)).hasSize(2).contains(storedHash(userId));
        assertThat(status(get("/api/auth/me"), confined)).isEqualTo(401);

        // The connector is refused the current password, which the User set, and the retained
        // one, which it set itself; a stale conditional write is refused before either is judged.
        Cookie settled = logIn("lifecycle-shared", NEW_PASSWORD, "USER", false);
        String hash = storedHash(userId);
        long version = scimVersion(userId);
        List<String> history = historyOf(userId);
        assertConnectorRefusedReuse(scimPatchPassword(userId, NEW_PASSWORD, null), NEW_PASSWORD);
        assertConnectorRefusedReuse(
                scimPutPassword(userId, "lifecycle-shared", CONNECTOR_PASSWORD), CONNECTOR_PASSWORD);
        assertThat(scimPatchPassword(userId, "a-stale-write-pass", "\"" + (version - 1) + "\"")
                .getResponse().getStatus()).isEqualTo(412);
        assertThat(storedHash(userId)).isEqualTo(hash);
        assertThat(scimVersion(userId)).isEqualTo(version);
        assertThat(historyOf(userId)).isEqualTo(history);
        assertThat(changeRequired(userId)).isFalse();
        assertThat(status(get("/api/auth/me"), settled))
                .as("no refused write ends a session")
                .isEqualTo(200);
        assertThat(auditCodes("SCIM_USER_REPLACE", userId))
                .containsExactlyInAnyOrder("INVALID_VALUE", "INVALID_VALUE");

        // An accepted connector write: flagged again, remembered once, sessions ended.
        assertThat(scimPatchPassword(userId, second, null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(changeRequired(userId)).isTrue();
        assertThat(historyOf(userId)).hasSize(3).contains(storedHash(userId));
        assertThat(status(get("/api/auth/me"), settled)).isEqualTo(401);

        // The User is refused both earlier passwords, whichever path set them.
        List<String> afterConnectorWrite = historyOf(userId);
        Cookie again = logIn("lifecycle-shared", second, null, true);
        for (String reused : List.of(NEW_PASSWORD, CONNECTOR_PASSWORD)) {
            MvcResult refused = changePassword(again, second, reused);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(json.readTree(refused.getResponse().getContentAsString())
                    .get("rule").asText()).isEqualTo("REUSED");
        }
        assertThat(historyOf(userId)).isEqualTo(afterConnectorWrite);
        assertThat(changeRequired(userId)).isTrue();
        assertThat(status(get("/api/auth/me"), again)).isEqualTo(200);
        assertThat(auditCodes("PASSWORD_CHANGE", userId))
                .containsExactlyInAnyOrder(null, "REUSED", "REUSED");

        String audited = jdbc.queryForList(
                "SELECT * FROM audit_events WHERE subject_id = ?::uuid", userId).toString();
        for (String secret : List.of(CONNECTOR_PASSWORD, NEW_PASSWORD, second, storedHash(userId))) {
            assertThat(audited).doesNotContain(secret);
        }
        assertThat(audited).doesNotContain("argon2");
    }

    /**
     * An accepted password is remembered inside the write's transaction, so a write that does not
     * commit takes its history entry with it, leaves the credential and flag as they were, and
     * ends no session — on both paths.
     */
    @Test
    void anAcceptedPasswordWhoseWriteRollsBackLeavesCredentialHistoryAndSessionsAlone()
            throws Exception {
        String userId = provisionAndSettle("lifecycle-rolled-back", CONNECTOR_PASSWORD, NEW_PASSWORD);
        UUID id = UUID.fromString(userId);
        Cookie session = logIn("lifecycle-rolled-back", NEW_PASSWORD, "USER", false);
        String hash = storedHash(userId);
        List<String> history = historyOf(userId);
        Timestamp basis = lastAuthenticatedAt(userId);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            scimUsers.patch(new AuthenticatedConnector(
                            connectorId, UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL)),
                    id, ScimVersionPrecondition.ofIfMatch(List.of()),
                    List.of(new ScimUserPatchOperation.SetPassword("rolled-back-connector-1")));
            assertThat(passwordHistory.findRecentHashes(id))
                    .as("remembered within the connector's write")
                    .hasSize(history.size() + 1);
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status -> {
            passwordChanges.changePassword(id, NEW_PASSWORD, "rolled-back-self-chosen");
            assertThat(passwordHistory.findRecentHashes(id))
                    .as("remembered within the self-service change")
                    .hasSize(history.size() + 1);
            status.setRollbackOnly();
        });

        assertThat(storedHash(userId)).isEqualTo(hash);
        assertThat(historyOf(userId)).isEqualTo(history);
        assertThat(changeRequired(userId)).isFalse();
        assertThat(lastAuthenticatedAt(userId)).isEqualTo(basis);
        assertThat(status(get("/api/auth/me"), session))
                .as("revocation waits for a commit that never came")
                .isEqualTo(200);
        assertThat(sessionRepository.findByPrincipalName(userId)).isNotEmpty();
        assertThat(logInStatus("lifecycle-rolled-back", "rolled-back-self-chosen")).isEqualTo(401);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void assertConnectorRefusedReuse(MvcResult result, String candidate) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = result.getResponse().getContentAsString();
        assertThat(json.readTree(body).get("scimType").asText()).isEqualTo("invalidValue");
        assertThat(body).doesNotContain(candidate).doesNotContain("argon2");
    }

    private MvcResult scimPatchPassword(String userId, String password, String ifMatch)
            throws Exception {
        MockHttpServletRequestBuilder request = patch(BASE + "/Users/" + userId)
                .contentType(SCIM_JSON)
                .content("""
                        {"schemas":["%s"],
                         "Operations":[{"op":"replace","path":"password","value":"%s"}]}"""
                        .formatted(PATCH_SCHEMA, password));
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(asConnector(request)).andReturn();
    }

    private MvcResult scimPutPassword(String userId, String userName, String password)
            throws Exception {
        return mvc.perform(asConnector(put(BASE + "/Users/" + userId))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"%s","password":"%s"}"""
                                .formatted(USER_SCHEMA, userName, password)))
                .andReturn();
    }

    /** The User's remembered hashes, newest first, as the database holds them. */
    private List<String> historyOf(String userId) {
        return jdbc.queryForList("""
                SELECT password_hash FROM scim_user_password_history WHERE user_id = ?::uuid
                ORDER BY set_at DESC, id""", String.class, userId);
    }

    private String storedHash(String userId) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM scim_users WHERE resource_id = ?::uuid",
                String.class, userId);
    }

    private boolean changeRequired(String userId) {
        return jdbc.queryForObject("""
                SELECT password_change_required_since IS NOT NULL FROM scim_users
                WHERE resource_id = ?::uuid""", Boolean.class, userId);
    }

    /** The error codes of this operation's events about the User, in no particular order. */
    private List<String> auditCodes(String operation, String userId) {
        return jdbc.queryForList("""
                SELECT error_code FROM audit_events WHERE operation = ? AND subject_id = ?::uuid""",
                String.class, operation, userId);
    }
    /**
     * Every route but the three the confined session holds is refused with 403 — the user
     * endpoints, the administrative interface, and the operational scrape — while the session
     * itself is valid and reports its confinement.
     */
    private String idOf(String userName) {
        return jdbc.queryForObject(
                "SELECT resource_id::text FROM scim_users WHERE normalized_user_name = ?",
                String.class, userName.toLowerCase(java.util.Locale.ROOT));
    }

    private void assertConfined(Cookie session) throws Exception {
        MvcResult me = send(get("/api/auth/me"), session);
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(me.getResponse().getContentAsString());
        assertThat(body.get("passwordChangeRequired").booleanValue()).isTrue();
        assertThat(body.has("role")).isFalse();
        assertThat(body.get("permissions").isEmpty()).isTrue();

        assertThat(status(get("/api/admin/accounts"), session)).isEqualTo(403);
        assertThat(status(post("/api/admin/accounts/{id}/unlock", idOf(BOOTSTRAP_ADMIN)), session))
                .isEqualTo(403);
        assertThat(status(get("/api/admin/connectors"), session)).isEqualTo(403);
        assertThat(status(get("/api/count"), session)).isEqualTo(403);
        assertThat(status(post("/api/count/increment"), session)).isEqualTo(403);
        assertThat(status(get("/api/session"), session)).isEqualTo(403);
        assertThat(status(get("/actuator/prometheus"), session)).isEqualTo(403);
    }

    /** Creates a User over SCIM with a connector-set password, which flags it. */
    private String provision(String userName, String password) throws Exception {
        MvcResult result = mvc.perform(asConnector(post(BASE + "/Users"))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"%s","password":"%s"}"""
                                .formatted(USER_SCHEMA, userName, password)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        created.add(UUID.fromString(id));
        return id;
    }

    /** Provisions, then completes the required change, leaving an unflagged User. */
    private String provisionAndSettle(String userName, String connectorPassword, String own)
            throws Exception {
        String id = provision(userName, connectorPassword);
        Cookie confined = logIn(userName, connectorPassword, null, true);
        assertThat(changePassword(confined, connectorPassword, own).getResponse().getStatus())
                .isEqualTo(204);
        return id;
    }

    private void addToAdminGroup(String userId) throws Exception {
        String adminGroupId = jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name IS NOT NULL AND resource_type = ?",
                UUID.class, "Group").toString();
        MvcResult result = mvc.perform(asConnector(patch(BASE + "/Groups/" + adminGroupId))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],
                                 "Operations":[{"op":"add","path":"members","value":[{"value":"%s"}]}]}"""
                                .formatted(PATCH_SCHEMA, userId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    /** Logs in for real, asserting the reported role and confinement, and returns the cookie. */
    private Cookie logIn(String userName, String password, String role, boolean confined)
            throws Exception {
        return logIn(withCsrf(post("/api/auth/login")), userName, password, role, confined);
    }

    private Cookie logIn(MockHttpServletRequestBuilder request, String userName, String password,
            String role, boolean confined) throws Exception {
        MvcResult login = mvc.perform(request
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, password)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("passwordChangeRequired").booleanValue()).isEqualTo(confined);
        // There is no role field: "ADMIN" here means a member of the Superuser Group, which holds
        // every Permission; "USER", a session holding only the baseline counter Permissions every
        // User holds; null, a confined session holding none.
        assertThat(body.has("role")).isFalse();
        int expected = role == null ? 0 : "ADMIN".equals(role) ? Permission.values().length : 2;
        assertThat(body.get("permissions").size()).isEqualTo(expected);
        Cookie session = login.getResponse().getCookie(sessionCookieName);
        assertThat(session).as("the login issued a session cookie").isNotNull();
        return new Cookie(session.getName(), session.getValue());
    }

    /**
     * A live session indexed by the User's stable id, saved straight to the store as a login would
     * leave it — the one way left to hold a second session, since a second login ends the first.
     */
    private String openSessionFor(String userId) {
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> repository =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;
        Session session = repository.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId);
        repository.save(session);
        return session.getId();
    }

    private int logInStatus(String userName, String password) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, password)))
                .andReturn().getResponse().getStatus();
    }

    private MvcResult changePassword(Cookie session, String current, String next) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/change-password"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                .formatted(current, next)))
                .andReturn();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return mvc.perform(withCsrf(request).cookie(session)).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return send(request, session).getResponse().getStatus();
    }

    private Timestamp lastAuthenticatedAt(String userId) {
        return jdbc.queryForObject(
                "SELECT last_authenticated_at FROM scim_users WHERE resource_id = ?::uuid",
                Timestamp.class, userId);
    }

    private long scimVersion(String userId) {
        return jdbc.queryForObject(
                "SELECT version FROM scim_resources WHERE id = ?::uuid", Long.class, userId);
    }

    private int failedAttempts(String userId) {
        return jdbc.queryForObject(
                "SELECT failed_login_attempts FROM scim_users WHERE resource_id = ?::uuid",
                Integer.class, userId);
    }

    private MockHttpServletRequestBuilder asConnector(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken)
                .with(ScimConditionalWrites.currentVersion(jdbc));
    }

    /**
     * A CSRF token on every request, safe or not — the cookie and the header must travel together,
     * and a request carrying the session cookie is otherwise indistinguishable from a browser's.
     */
    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}
