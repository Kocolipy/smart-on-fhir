package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.DatabaseFailureCategory;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.scim.application.ConnectorAdministrationService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A SCIM write the database really chooses as a deadlock's victim, and the retry ADR 0015's as-13
 * puts at its outermost transactional boundary.
 *
 * <p>The deadlock is forced rather than raced for. A User PUT locks the User and its Group one
 * row at a time, in the lock module's order ({@link ScimWriteLocks#ORDER}). A second connection,
 * standing for another writer, first holds the row the PUT locks second; once the PUT waits on
 * it, the second connection asks for the row the PUT already holds, and the two wait on each
 * other. PostgreSQL resolves a deadlock by failing the transaction whose wait has lasted the
 * deadlock timeout first — the PUT's, which began waiting before the other did — as
 * {@link com.example.backend.observability.SqlFailures#DEADLOCK_VICTIM}.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimDeadlockRetryIntegrationTests {

    private static final String DEADLOCK_RETRIED = "Database deadlock: the write is retried once";

    private static final ScimVersionPrecondition UNCONDITIONAL =
            ScimVersionPrecondition.ofIfMatch(List.of());

    private static final String LOCK_ROW = "SELECT id FROM scim_resources WHERE id = ? FOR UPDATE";

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
     * was retried.
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
            int otherPid = pid(other);
            lock(other, inLockOrder.get(1));

            put = CompletableFuture.supplyAsync(() -> userService.replace(connector, user,
                    UNCONDITIONAL, new ScimUserReplacement(
                            profile("deadlock-retry-" + tag, "Retried " + tag), null, null,
                            false)));
            awaitBlockedBy(otherPid);

            // Closes the cycle; returns once the database has failed the PUT's transaction.
            lock(other, inLockOrder.get(0));
            other.rollback();
        }

        assertThat(put.get(30, TimeUnit.SECONDS))
                .hasValueSatisfying(written ->
                        assertThat(written.profile().displayName()).isEqualTo("Retried " + tag));
        assertThat(retried()).singleElement().satisfies(warn -> {
            assertThat(warn.at("/log/level").asText()).isEqualTo("WARN");
            assertThat(warn.at("/error/category").asText()).isEqualTo("database");
            assertThat(warn.at("/app/retry/attempt").asInt()).isEqualTo(1);
            assertThat(warn.at("/event/reason").asText()).isEqualTo("deadlock");
        });
    }

    /**
     * The PUT's retry chosen as the victim again: the second deadlock is the write's failure and
     * reaches its caller as the database raised it — for the request's fault handler to write as
     * a {@code database} {@code ERROR} — and the write is not tried a third time, so the stream
     * carries the one {@code WARN} of the one retry.
     */
    @Test
    void aUserPutChosenAsTheVictimAgainOnItsRetryFails() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID user = createUser("deadlock-repeat-" + tag);
        UUID group = createGroup("Deadlock repeat " + tag, List.of(user));
        List<UUID> inLockOrder = inLockOrder(user, group);

        CompletableFuture<Optional<ScimUserResource>> put;
        try (Connection other = anotherWriter()) {
            other.setAutoCommit(false);
            int otherPid = pid(other);
            lock(other, inLockOrder.get(1));

            put = CompletableFuture.supplyAsync(() -> userService.replace(connector, user,
                    UNCONDITIONAL, new ScimUserReplacement(
                            profile("deadlock-repeat-" + tag, "Repeated " + tag), null, null,
                            false)));
            awaitBlockedBy(otherPid);

            // The first deadlock: the PUT's first transaction fails, and its retry waits on the
            // row held here under the savepoint.
            Savepoint closing = other.setSavepoint();
            lock(other, inLockOrder.get(0));
            awaitBlockedBy(otherPid);

            // Lets the retry take that row and wait on the other again, then closes the cycle a
            // second time.
            other.rollback(closing);
            awaitHeldElsewhere(other, inLockOrder.get(0));
            awaitBlockedBy(otherPid);
            lock(other, inLockOrder.get(0));
            other.rollback();
        }

        assertThatThrownBy(() -> put.get(30, TimeUnit.SECONDS))
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(DatabaseFailureCategory.deadlock(sql)).isTrue());
        assertThat(retried()).hasSize(1);
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

    /** Waits until some backend is waiting on a lock {@code pid} holds. */
    private void awaitBlockedBy(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                Integer.class, pid) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the PUT never waited on the other writer's lock");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Waits until another transaction holds {@code id}'s row lock, probing with a lock that does
     * not wait, under a savepoint that gives back whatever the probe took.
     */
    private static void awaitHeldElsewhere(Connection connection, UUID id)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            Savepoint probe = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(LOCK_ROW + " NOWAIT")) {
                statement.setObject(1, id);
                statement.executeQuery().close();
            } catch (SQLException held) {
                connection.rollback(probe);
                return;
            }
            connection.rollback(probe);
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the PUT's retry never took the row");
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

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
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
