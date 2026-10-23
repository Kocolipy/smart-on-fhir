package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.ForcedDeadlock;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.IssuedConnectorToken;
import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.application.ScimGroupService;
import com.example.backend.scim.application.ScimUserReplacement;
import com.example.backend.scim.application.ScimUserResource;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import com.example.backend.scim.domain.ScimWriteLocks;
import jakarta.servlet.Filter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * A SCIM write the database really chooses as a deadlock's victim, and the retry ADR 0013's as-13
 * puts at its outermost transactional boundary.
 *
 * <p>The first deadlock is forced rather than raced for. A User PUT locks the User and its Group
 * one row at a time, in the lock module's order ({@link ScimWriteLocks#ORDER}). A second
 * connection, standing for another writer, first holds the row the PUT locks second; once the PUT
 * waits on it — observed in {@code sys.dm_exec_requests}, never assumed after a sleep — the
 * second connection asks for the row the PUT already holds, and the two wait on each other. The
 * other writer runs at {@code DEADLOCK_PRIORITY HIGH}, so SQL Server breaks the cycle by failing
 * the PUT's transaction with error 1205. A deadlock on the retry as well is {@link ForcedDeadlock}'s:
 * a real deadlock too, met by every attempt to write the row.
 *
 * <p>Neither outcome may carry the driver's text — SQL Server's victim message names its process
 * and says what it was chosen as — to the caller or into the log.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimDeadlockRetryIntegrationTests {

    private static final String DEADLOCK_RETRIED = "Database deadlock: the write is retried once";

    /** What SQL Server's victim message says, none of which may reach a caller or a record. */
    private static final List<String> DRIVER_TEXT =
            List.of("deadlock victim", "deadlocked", "Process ID", "Rerun the transaction");

    private static final ScimVersionPrecondition UNCONDITIONAL =
            ScimVersionPrecondition.ofIfMatch(List.of());

    private static final String LOCK_ROW =
            "SELECT id FROM scim_resources WITH (UPDLOCK, ROWLOCK) WHERE id = ?";

    /** A request waiting on a lock the given session holds. */
    private static final String BLOCKED_BY = """
            SELECT count(*) FROM sys.dm_exec_requests
             WHERE blocking_session_id = ? AND wait_type LIKE 'LCK[_]M[_]%'""";

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimUserService userService;

    @Autowired
    private ScimGroupService groupService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private DatabaseLogins logins;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private AuthenticatedConnector connector;

    private final List<UUID> created = new ArrayList<>();

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create("Deadlock retry Okta", null).id();
        connector = new AuthenticatedConnector(
                connectorId, UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        logs.close();
        for (UUID id : created) {
            ScimTestRows.deleteUnreserved(jdbc, id);
        }
        created.clear();
    }

    /**
     * The PUT chosen as the victim is retried once, in a transaction of its own, and completes:
     * its caller sees the write applied, and the stream carries the one {@code WARN} that says it
     * was retried, quoting nothing of the driver's.
     */
    @Test
    void aUserPutChosenAsADeadlockVictimIsRetriedOnceAndCompletes() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID user = createUser("deadlock-retry-" + tag);
        UUID group = createGroup("Deadlock retry " + tag, List.of(user));
        List<UUID> inLockOrder = inLockOrder(user, group);

        CompletableFuture<Optional<ScimUserResource>> put;
        try (Connection other = anotherWriter()) {
            other.setAutoCommit(false);
            int otherSession = session(other);
            lock(other, inLockOrder.get(1));

            put = CompletableFuture.supplyAsync(() -> userService.replace(connector, user,
                    UNCONDITIONAL, new ScimUserReplacement(
                            profile("deadlock-retry-" + tag, "Retried " + tag), null, null,
                            false)));
            awaitBlockedBy(otherSession);

            // Closes the cycle; returns once the database has failed the PUT's transaction.
            lock(other, inLockOrder.get(0));
            other.rollback();
        }

        assertThat(put.get(60, TimeUnit.SECONDS))
                .hasValueSatisfying(written ->
                        assertThat(written.profile().displayName()).isEqualTo("Retried " + tag));
        assertThat(retried()).singleElement().satisfies(warn -> {
            assertThat(warn.at("/log/level").asText()).isEqualTo("WARN");
            assertThat(warn.at("/error/category").asText()).isEqualTo("database");
            assertThat(warn.at("/app/retry/attempt").asInt()).isEqualTo(1);
            assertThat(warn.at("/event/reason").asText()).isEqualTo("deadlock");
        });
        assertThat(logs.lines()).as("the stream").doesNotContain(DRIVER_TEXT);
    }

    /**
     * The PUT's retry chosen as the victim again: ADR 0013's as-13 second deadlock. The write is
     * not tried a third time, and its connector is answered {@code 503} with
     * {@code Retry-After: 1} in the SCIM error document, with no driver text — and the stream
     * carries the one retry's {@code WARN} and the fault's one {@code ERROR}, {@code database},
     * under the status answered.
     */
    @Test
    void aUserPutChosenAsTheVictimAgainOnItsRetryIsA503WithRetryAfter() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID user = createUser("deadlock-repeat-" + tag);
        String token = connectors.issueToken(connector.connectorId(), TokenPermissions.ALL, null,
                null).presentedValue();
        String replacement = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],\
                "userName":"deadlock-repeat-%s","displayName":"Repeated %s","active":true}"""
                .formatted(tag, tag);

        MockHttpServletResponse answered;
        try (ForcedDeadlock victim = ForcedDeadlock.always(logins, "scim_users", "resource_id", user)) {
            answered = putOverHttp(token, user, replacement).getResponse();
        }

        assertThat(answered.getStatus()).isEqualTo(503);
        assertThat(answered.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(answered.getContentType()).startsWith("application/scim+json");
        assertThat(contentOf(answered))
                .contains("\"status\":\"503\"")
                .doesNotContain(DRIVER_TEXT)
                .as("the error number is the log's, not the caller's").doesNotContain("1205")
                .doesNotContain("scimType");
        assertThat(retried()).hasSize(1);
        assertThat(logs.records().stream()
                        .filter(record -> "ERROR".equals(record.at("/log/level").asText()))
                        .toList())
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.at("/error/code").asInt()).isEqualTo(503);
                    assertThat(error.at("/error/category").asText()).isEqualTo("database");
                });
        assertThat(logs.lines()).as("the stream").doesNotContain(DRIVER_TEXT);
    }

    /**
     * A connector administration write is retried as a SCIM write is: a token rotation whose
     * first attempt is the victim completes on its retry, and the retry's {@code WARN} is
     * classified as the rotation.
     */
    @Test
    void aTokenRotationChosenAsADeadlockVictimIsRetriedOnceAndCompletes() {
        UUID issued = connectors.issueToken(connector.connectorId(), TokenPermissions.ALL, null,
                null).tokenId();

        IssuedConnectorToken replacement;
        try (ForcedDeadlock victim = ForcedDeadlock.once(
                logins, "scim_connector_tokens", "connector_id", connector.connectorId())) {
            replacement = connectors.rotateToken(connector.connectorId(), issued, null, null, null);
        }

        assertThat(replacement.tokenId()).isNotEqualTo(issued);
        assertThat(retried()).singleElement().satisfies(warn ->
                assertThat(warn.at("/app/event/action").asText())
                        .isEqualTo("scim.connector.token.rotate"));
    }

    // ---- harness -----------------------------------------------------------------------------

    /**
     * The order the PUT takes these rows in — the lock module's own, so the test holds the right
     * row on any engine rather than on whichever one sorts {@code uuid}s the module's way.
     */
    private static List<UUID> inLockOrder(UUID... ids) {
        return Stream.of(ids).sorted(ScimWriteLocks.ORDER).toList();
    }

    private List<JsonNode> retried() {
        return logs.records().stream()
                .filter(record -> DEADLOCK_RETRIED.equals(record.at("/message").asText()))
                .toList();
    }

    /**
     * Waits until some request is waiting on a lock {@code session} holds. Read as the server's
     * administrator: another login's requests are visible only with {@code VIEW SERVER STATE}.
     */
    private void awaitBlockedBy(int session) throws InterruptedException {
        JdbcTemplate observer = logins.superuser().jdbc();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (observer.queryForObject(BLOCKED_BY, Integer.class, session) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the PUT never waited on the other writer's lock");
            }
            Thread.sleep(10);
        }
    }

    private static void lock(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ROW)) {
            statement.setObject(1, id);
            statement.executeQuery().close();
        }
    }

    /**
     * The connection's session, and its deadlock priority raised: of the two writers in the
     * cycle, the server fails the one of lower priority, which is the PUT.
     */
    private static int session(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET DEADLOCK_PRIORITY HIGH");
            try (ResultSet result = statement.executeQuery("SELECT @@SPID")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /**
     * Another writer's connection, outside the service's pools, as the app login every writer of
     * this service connects as — which holds the row locks a writer takes.
     */
    private Connection anotherWriter() throws SQLException {
        return DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
    }

    /** The PUT as a connector sends it, over the namespace's filter chain. */
    private MvcResult putOverHttp(String token, UUID user, String body) {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        try {
            return mvc.perform(MockMvcRequestBuilders.put("/scim/v2/Users/" + user)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.valueOf("application/scim+json"))
                            .content(body))
                    .andReturn();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static String contentOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (java.io.UnsupportedEncodingException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private UUID createUser(String userName) {
        UUID id = userService.create(connector,
                new NewScimUser(profile(userName, null), null, null)).id();
        created.add(id);
        return id;
    }

    private UUID createGroup(String displayName, List<UUID> members) {
        UUID id = groupService.create(connector, new NewScimGroup(displayName, members, null)).id();
        created.add(id);
        return id;
    }

    private static ScimUserProfile profile(String userName, String displayName) {
        return new ScimUserProfile(userName, null, displayName, null, null, null, true, List.of());
    }
}
