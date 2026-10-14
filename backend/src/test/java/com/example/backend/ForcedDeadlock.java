package com.example.backend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A row whose every write — or only its first — the database chooses as a deadlock's victim, for
 * a test that needs a write to be one on demand, over the whole stack: the driver, Spring's
 * translation, the deadlock retry and the request's fault handler.
 *
 * <p>The deadlock is real: SQL Server lets no statement raise error 1205 itself. A trigger, made
 * by the owner login, fires on an insert, update or delete of a row of {@code table} whose
 * {@code column} reads {@code value} — so nothing another test writes is touched — and in the
 * writer's transaction takes an application lock and then asks for a row of a table of its own.
 * A partner connection holds that row. Once the writer waits on it, observed in
 * {@code sys.dm_exec_requests}, the partner asks for the application lock the writer holds, and
 * the two wait on each other. The partner runs at {@code DEADLOCK_PRIORITY HIGH}, so the server
 * breaks the cycle by failing the writer's transaction with error 1205 — exactly as it fails a
 * deadlock between two of the service's own writers, which is
 * {@code ScimDeadlockRetryIntegrationTests}'. The partner then gives the application lock back
 * and waits for the next writer, or, for {@link #once}, lets go of its row so every later write
 * goes through. Closing stops the partner and drops the trigger and its table.
 */
public final class ForcedDeadlock implements AutoCloseable {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_]+");

    /** A session waiting for the partner's row: an update lock it cannot be granted. */
    private static final String WAITING_ON_THE_PARTNER = """
            SELECT count(*) FROM sys.dm_exec_requests
             WHERE blocking_session_id = ? AND wait_type = 'LCK_M_U'""";

    /** The partner asking for the application lock the writer's trigger holds, as long as it takes. */
    private static final String TAKE_THE_WRITERS_LOCK = """
            EXEC sp_getapplock @Resource = ?, @LockMode = N'Exclusive', @LockOwner = N'Session',
                @LockTimeout = -1""";

    private static final String GIVE_IT_BACK =
            "EXEC sp_releaseapplock @Resource = ?, @LockOwner = N'Session'";

    private final JdbcTemplate owner;

    private final String name;

    private final AtomicBoolean closed;

    private final CompletableFuture<Void> partner;

    private ForcedDeadlock(JdbcTemplate owner, String name, AtomicBoolean closed,
            CompletableFuture<Void> partner) {
        this.owner = owner;
        this.name = name;
        this.closed = closed;
        this.partner = partner;
    }

    /** Every write of the row fails as a deadlock's victim: a retried write's retry included. */
    public static ForcedDeadlock always(
            DatabaseLogins logins, String table, String column, Object value) {
        return install(logins, table, column, value, false);
    }

    /** The row's first write fails as a deadlock's victim, and every later one goes through. */
    public static ForcedDeadlock once(
            DatabaseLogins logins, String table, String column, Object value) {
        return install(logins, table, column, value, true);
    }

    private static ForcedDeadlock install(
            DatabaseLogins logins, String table, String column, Object value, boolean once) {
        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("a table and a column name only");
        }
        String literal = value.toString().replace("'", "''");
        String name = "forced_deadlock_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate owner = logins.owner().jdbc();
        owner.execute("CREATE TABLE " + name + " (id INT NOT NULL)");
        owner.execute("INSERT INTO " + name + " (id) VALUES (1)");
        // Owned by the schema's owner, as the table is, so the writer — the app login — reaches
        // the table through the trigger's ownership chain and needs no right to it.
        owner.execute("""
                CREATE TRIGGER %1$s_trigger ON %2$s AFTER INSERT, UPDATE, DELETE AS
                BEGIN
                    SET NOCOUNT ON;
                    IF EXISTS (SELECT 1 FROM inserted
                                WHERE LOWER(CONVERT(NVARCHAR(4000), %3$s)) = LOWER(N'%4$s'))
                       OR EXISTS (SELECT 1 FROM deleted
                                   WHERE LOWER(CONVERT(NVARCHAR(4000), %3$s)) = LOWER(N'%4$s'))
                    BEGIN
                        DECLARE @granted INT, @row INT;
                        EXEC @granted = sp_getapplock @Resource = N'%1$s',
                            @LockMode = N'Exclusive', @LockOwner = N'Transaction',
                            @LockTimeout = -1;
                        SELECT @row = id FROM %1$s WITH (UPDLOCK, ROWLOCK);
                    END
                END""".formatted(name, table, column, literal));
        AtomicBoolean closed = new AtomicBoolean();
        PartnerStart started = new PartnerStart();
        CompletableFuture<Void> partner = CompletableFuture.runAsync(() -> partner(
                logins.owner().jdbc().getDataSource(), logins.superuser().jdbc().getDataSource(),
                name, once, closed, started), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    thread.start();
                });
        started.await(partner);
        return new ForcedDeadlock(owner, name, closed, partner);
    }

    /**
     * The partner: holds the row, and closes the cycle with each writer that comes to wait on
     * it, until it is closed — or after the first, {@code once}.
     */
    private static void partner(DataSource asOwner, DataSource asObserver, String name,
            boolean once, AtomicBoolean closed, PartnerStart started) {
        try (Connection holder = asOwner.getConnection();
                Connection observer = asObserver.getConnection()) {
            holder.setAutoCommit(false);
            int session;
            try (Statement statement = holder.createStatement()) {
                statement.execute("SET DEADLOCK_PRIORITY HIGH");
                // A false positive of formatted-sql-string: the table is this class's own, named
                // from a random UUID's hex digits, never from anything a caller passed.
                statement.executeQuery("SELECT id FROM " + name // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                        + " WITH (UPDLOCK, ROWLOCK)").close();
                try (ResultSet spid = statement.executeQuery("SELECT @@SPID")) {
                    spid.next();
                    session = spid.getInt(1);
                }
            }
            started.done();
            while (!closed.get()) {
                if (!waitedOn(observer, session)) {
                    Thread.sleep(10);
                    continue;
                }
                // Waits on the writer's application lock: the cycle the server breaks by failing
                // the writer, after which this is granted and given straight back.
                try (PreparedStatement take = holder.prepareStatement(TAKE_THE_WRITERS_LOCK);
                        PreparedStatement giveBack = holder.prepareStatement(GIVE_IT_BACK)) {
                    take.setString(1, name);
                    take.execute();
                    giveBack.setString(1, name);
                    giveBack.execute();
                }
                if (once) {
                    break;
                }
            }
            holder.rollback();
        } catch (SQLException | InterruptedException failed) {
            started.failed(failed);
            throw new IllegalStateException("the forced deadlock's partner failed", failed);
        }
    }

    private static boolean waitedOn(Connection observer, int session) throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement(WAITING_ON_THE_PARTNER)) {
            statement.setInt(1, session);
            try (ResultSet waiting = statement.executeQuery()) {
                waiting.next();
                return waiting.getInt(1) > 0;
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            partner.get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new IllegalStateException("the forced deadlock's partner did not stop", failed);
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS " + name + "_trigger");
            owner.execute("DROP TABLE IF EXISTS " + name);
        }
    }

    /** The partner's hold on its row, which a writer may meet only once it is in place. */
    private static final class PartnerStart {

        private final CompletableFuture<Void> holding = new CompletableFuture<>();

        void done() {
            holding.complete(null);
        }

        void failed(Throwable failure) {
            holding.completeExceptionally(failure);
        }

        void await(CompletableFuture<Void> partner) {
            try {
                CompletableFuture.anyOf(holding, partner).get(30, TimeUnit.SECONDS);
            } catch (Exception failed) {
                throw new IllegalStateException("the forced deadlock's partner never held its row",
                        failed);
            }
        }
    }
}
