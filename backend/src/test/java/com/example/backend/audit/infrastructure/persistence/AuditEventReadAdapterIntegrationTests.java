package com.example.backend.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.database.config.DatabaseConnections;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read adapter on its own, against the real SQL Server, constructed by the test rather than
 * taken from the context — so the construction itself is something a test observes, not a side
 * effect of a context another test class may already have built.
 *
 * <p>A listing runs as the listing service runs it: in one read-only transaction of the data
 * source the adapter reads.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class AuditEventReadAdapterIntegrationTests {

    /** The {@code transaction_isolation_level} SQL Server reports for {@code READ COMMITTED}. */
    private static final short READ_COMMITTED = 2;

    /** The isolation level of the session asking: a login may always read its own. */
    private static final String OWN_ISOLATION_LEVEL = """
            SELECT transaction_isolation_level FROM sys.dm_exec_sessions
             WHERE session_id = @@SPID""";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseConnections connections;

    private final UUID actor = UUID.randomUUID();

    private final Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.HOURS);

    @Test
    void readsTheStoredRowsOfAFreshlyConstructedAdapter() {
        UUID older = insert(at.minusSeconds(1), "active,password");
        UUID newer = insert(at, null);

        AuditEventPage page = listing(dataSource, query(0, 10));

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.events()).extracting(AuditEvent::id).containsExactly(newer, older);
        AuditEvent event = page.events().get(1);
        assertThat(event.occurredAt()).isEqualTo(at.minusSeconds(1));
        assertThat(event.operation()).isEqualTo(AuditOperation.LOCKOUT_SET);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.actorId()).isEqualTo(actor);
        assertThat(event.changedPaths()).containsExactly("active", "password");
        assertThat(page.events().get(0).changedPaths()).isEmpty();
    }

    /** A page past the end holds nothing, while the total still counts every match. */
    @Test
    void aPagePastTheEndIsEmptyAndStillCountsTheMatches() {
        insert(at, null);
        insert(at.minusSeconds(1), null);

        assertThat(listing(dataSource, query(1, 1)).events()).hasSize(1);
        AuditEventPage past = listing(dataSource, query(2, 1));
        assertThat(past.events()).isEmpty();
        assertThat(past.totalElements()).isEqualTo(2);
        assertThat(past.totalPages()).isEqualTo(2);
    }

    /**
     * An event appended between the listing's count and its page — committed by another
     * connection the moment the listing's first statement has run — is in neither: the two
     * statements read one snapshot, so the total an Admin is shown describes exactly the events
     * beside it (ADR 0013). Under read-committed snapshot alone each statement would read its own,
     * and the page would hold an event the total did not count.
     */
    @Test
    void anEventAppendedBetweenTheCountAndThePageIsInNeither() {
        insert(at.minusSeconds(2), null);
        insert(at.minusSeconds(1), null);
        DataSource appendingAfterTheCount =
                afterTheFirstStatement(dataSource, () -> insert(at, null));

        AuditEventPage page = listing(appendingAfterTheCount, query(0, 10));

        assertThat(List.of(page.totalElements(), (long) page.events().size()))
                .as("the total and the page, of the two events there were when the listing began")
                .containsExactly(2L, 2L);
    }

    /**
     * The listing's snapshot is its transaction's alone: the pooled connection it ran on goes
     * back to {@code READ COMMITTED}, so the next transaction on it — a write, say, which under
     * snapshot isolation could fail on an update conflict — reads as every other one does.
     */
    @Test
    void aListingLeavesItsConnectionReadCommittedForTheNextTransaction() {
        try (HikariDataSource onlyOne = connections.pool(
                "listing-isolation-probe", connections.appLogin(), 1, Duration.ofSeconds(10))) {
            listing(onlyOne, query(0, 10));

            Short level = readOnly(onlyOne).execute(status -> new JdbcTemplate(onlyOne)
                    .queryForObject(OWN_ISOLATION_LEVEL, Short.class));

            assertThat(level).isEqualTo(READ_COMMITTED);
        }
    }

    // ---- harness -----------------------------------------------------------------------------

    /** The listing as the listing service runs it: in one read-only transaction. */
    private static AuditEventPage listing(DataSource source, AuditEventQuery query) {
        return readOnly(source).execute(status -> new AuditEventReadAdapter(source).find(query));
    }

    private static TransactionTemplate readOnly(DataSource source) {
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        return transaction;
    }

    /**
     * {@code source}, but running {@code meanwhile} once, on the test's own connection, right
     * after the first query any of its connections executes has returned.
     */
    private static DataSource afterTheFirstStatement(DataSource source, Runnable meanwhile) {
        AtomicBoolean ran = new AtomicBoolean();
        return new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                Connection connection = super.getConnection();
                return proxy(Connection.class, connection, (method, result) ->
                        method.getName().equals("prepareStatement")
                                ? proxy(PreparedStatement.class, (PreparedStatement) result,
                                        (executed, rows) -> {
                                            if (executed.getName().equals("executeQuery")
                                                    && ran.compareAndSet(false, true)) {
                                                meanwhile.run();
                                            }
                                            return rows;
                                        })
                                : result);
            }
        };
    }

    /** What a proxy does with each call's result once its target has answered. */
    private interface AfterCall {
        Object after(Method method, Object result);
    }

    private static <T> T proxy(Class<T> type, T target, AfterCall after) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            try {
                return after.after(method, method.invoke(target, arguments));
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        };
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private AuditEventQuery query(int page, int size) {
        return new AuditEventQuery(null, null, actor, null, null, null, page, size);
    }

    private UUID insert(Instant occurredAt, String changedPaths) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, operation, outcome, actor_id,
                                          resource_type, changed_paths, status_class)
                VALUES (?, ?, 'LOCKOUT_SET', 'SUCCESS', ?, 'User', ?, 'client_error')
                """, id, occurredAt.atOffset(ZoneOffset.UTC), actor, changedPaths);
        return id;
    }
}
