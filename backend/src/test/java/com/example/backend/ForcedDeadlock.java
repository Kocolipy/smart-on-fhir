package com.example.backend;

import com.example.backend.observability.SqlFailures;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A row whose every write — or only its first — the database fails as a deadlock's victim, for a
 * test that needs a write to be one on demand, over the whole stack: the driver, Spring's
 * translation, the deadlock retry and the request's fault handler.
 *
 * <p>A trigger, made by the owner login, raises the victim's SQLState
 * ({@link SqlFailures#DEADLOCK_VICTIM}) on an insert, update or delete of a row of {@code table}
 * whose {@code column} reads {@code value}, so nothing another test writes is touched. The engine
 * reports it exactly as it reports a deadlock it broke, which is all the service keys on
 * ({@code DatabaseFailureCategory}); a real deadlock between two writers is
 * {@code ScimDeadlockRetryIntegrationTests}'. Closing drops it.
 */
public final class ForcedDeadlock implements AutoCloseable {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_]+");

    private final JdbcTemplate owner;

    private final String name;

    private final String table;

    private ForcedDeadlock(JdbcTemplate owner, String name, String table) {
        this.owner = owner;
        this.name = name;
        this.table = table;
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
        String raise = "RAISE EXCEPTION 'forced deadlock' USING ERRCODE = '%s';"
                .formatted(SqlFailures.DEADLOCK_VICTIM.sqlState());
        if (once) {
            owner.execute("CREATE SEQUENCE " + name);
            raise = "IF nextval('%s') = 1 THEN %s END IF;".formatted(name, raise);
        }
        // SECURITY DEFINER: the sequence is the owner's, and the app login writing the row needs
        // no right to it.
        owner.execute("""
                CREATE FUNCTION %1$s() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public AS $$
                DECLARE
                    written %2$s%%ROWTYPE;
                BEGIN
                    IF TG_OP = 'DELETE' THEN written := OLD; ELSE written := NEW; END IF;
                    IF written.%3$s::text = '%4$s' THEN
                        %5$s
                    END IF;
                    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
                    RETURN NEW;
                END $$""".formatted(name, table, column, literal, raise));
        owner.execute("CREATE TRIGGER %1$s BEFORE INSERT OR UPDATE OR DELETE ON %2$s"
                .formatted(name, table) + " FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        return new ForcedDeadlock(owner, name, table);
    }

    @Override
    public void close() {
        owner.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
        owner.execute("DROP FUNCTION IF EXISTS " + name + "()");
        owner.execute("DROP SEQUENCE IF EXISTS " + name);
    }
}
