package com.example.backend.observability;

import com.example.backend.observability.LogEvent.ErrorCategory;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Which {@code error.category} a failure the database raised belongs to — ADR 0013's mapping —
 * and whether it is a deadlock's victim, the one failure the deadlock retry repeats. Both are
 * answered from one table of the failures SQL Server reports, keyed on the SQLState of the
 * {@link SQLException} in the cause chain <em>and</em> its vendor error number
 * ({@link SQLException#getErrorCode()}), beside Spring's data-access and transaction hierarchy.
 * Never a driver's class.
 *
 * <p>Most rows key on the server's error number alone. The driver derives an SQLState for a
 * server error from the error's own state number ({@code S0001}, {@code S0005}, …) for all but a
 * handful of numbers, so the state says little, and it is shared: the deadlock victim (1205) and
 * the snapshot conflict (3960), which must not be retried, both arrive as {@code 40001}. A
 * failure the driver raised itself — a timeout, a lost connection — has no server number
 * ({@code 0}) and keys on its state.
 *
 * <ul>
 *   <li><strong>{@code cert/auth}</strong> — the database refused this service's own
 *       credential or rights, which needs its configuration checked rather than its code: a
 *       refused login (18456), a permission denied (229, or Spring's
 *       {@link PermissionDeniedDataAccessException}), and a TLS trust failure (an
 *       {@code SSLException} or {@code CertificateException} beneath the SQL exception).
 *   <li><strong>{@code database}</strong> — every other database failure: a deadlock or a
 *       snapshot conflict, an untranslated unique violation, a truncation or a conversion
 *       error, a lock not acquired or a statement timed out, the audit table's append-only
 *       trigger refusing a change (its own 50001, ADR 0004's 2026-10-14 addendum), and any
 *       failure Spring's data access or transaction layer raised with no SQL exception beneath
 *       it.
 *   <li><strong>{@code application}</strong> — anything else: this is not a database failure.
 * </ul>
 */
public final class DatabaseFailureCategory {

    /**
     * One row of the table: a failure as the engine reports it, and what the mapping makes of
     * it. {@code sqlState} is a five-character state or a two-character class, which matches
     * every state in it; {@code errorCode} is the vendor error number. Either is {@code null}
     * for any.
     */
    record EngineFailure(String sqlState, Integer errorCode, ErrorCategory category,
            boolean deadlock) {

        boolean reportedAs(SQLException sql) {
            String state = sql.getSQLState();
            return (sqlState == null || state != null && state.startsWith(sqlState))
                    && (errorCode == null || errorCode == sql.getErrorCode());
        }
    }

    /** Error 18456, login failed: the server refused this service's credential. */
    static final EngineFailure REFUSED_LOGIN =
            new EngineFailure(null, 18456, ErrorCategory.CERT_AUTH, false);

    /**
     * Error 229, a permission denied on an object: a privilege this service's login does not
     * hold.
     */
    static final EngineFailure PERMISSION_DENIED =
            new EngineFailure(null, 229, ErrorCategory.CERT_AUTH, false);

    /**
     * Error 1205, the transaction chosen as a deadlock's victim and rolled back: the one failure
     * retried. The driver reports it under {@code 40001}, which the row names too, so a failure
     * raised with the number under another state is not taken for one.
     */
    static final EngineFailure DEADLOCK_VICTIM =
            new EngineFailure("40001", 1205, ErrorCategory.DATABASE, true);

    /**
     * Error 3960, a snapshot transaction's update conflicting with another's: a conflict between
     * concurrent transactions that is no deadlock, and is not retried.
     */
    static final EngineFailure SNAPSHOT_CONFLICT =
            new EngineFailure(null, 3960, ErrorCategory.DATABASE, false);

    /**
     * Error 50001, the number the audit table's append-only trigger throws (V1): the trigger
     * refusing a change, never a permission refusal (ADR 0004's 2026-10-14 addendum).
     */
    static final EngineFailure AUDIT_TRIGGER_REFUSAL =
            new EngineFailure(null, 50001, ErrorCategory.DATABASE, false);

    /**
     * Error 2627, a {@code UNIQUE} or {@code PRIMARY KEY} constraint violated, which no adapter
     * translated. A unique index with no constraint behind it reports 2601 instead.
     */
    static final EngineFailure UNIQUE_CONSTRAINT_VIOLATION =
            new EngineFailure(null, 2627, ErrorCategory.DATABASE, false);

    /**
     * Error 2601, a duplicate key in a unique index that is no constraint ({@code CREATE UNIQUE
     * INDEX}), which no adapter translated.
     */
    static final EngineFailure UNIQUE_INDEX_VIOLATION =
            new EngineFailure(null, 2601, ErrorCategory.DATABASE, false);

    /** Error 2628, string or binary data truncated: a value too long for its column. */
    static final EngineFailure VALUE_TOO_LONG =
            new EngineFailure(null, 2628, ErrorCategory.DATABASE, false);

    /**
     * Error 245, "Conversion failed when converting the ... value '...' to data type ...": one
     * value that does not convert to the type it is compared or stored as, quoted in the message.
     */
    static final EngineFailure VALUE_CONVERSION_FAILED =
            new EngineFailure(null, 245, ErrorCategory.DATABASE, false);

    /**
     * Error 8114, "Error converting data type ... to ...": a conversion reported by its two types
     * alone, as a bound parameter of the wrong type or a conversion to {@code numeric} reports.
     */
    static final EngineFailure DATA_TYPE_CONVERSION_FAILED =
            new EngineFailure(null, 8114, ErrorCategory.DATABASE, false);

    /** Error 1222, a lock request whose time-out ran out: a lock not acquired. */
    static final EngineFailure LOCK_NOT_AVAILABLE =
            new EngineFailure(null, 1222, ErrorCategory.DATABASE, false);

    /** SQLState {@code HY008}, the driver cancelling a statement whose query timeout ran out. */
    static final EngineFailure STATEMENT_TIMEOUT =
            new EngineFailure("HY008", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 08S01}: an open connection lost. */
    static final EngineFailure CONNECTION_FAILURE =
            new EngineFailure("08S01", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 08001}: a connection the driver could not open. */
    static final EngineFailure CONNECTION_NOT_ESTABLISHED =
            new EngineFailure("08001", null, ErrorCategory.DATABASE, false);

    /**
     * The engine, SQL Server. A failure with no row here is a {@code database} failure, so the
     * {@code database} rows that are no deadlock change no answer: they name, once, the engine's
     * codes for the failures the service meets.
     */
    private static final DatabaseFailureCategory ENGINE = new DatabaseFailureCategory(List.of(
            REFUSED_LOGIN, PERMISSION_DENIED, DEADLOCK_VICTIM, SNAPSHOT_CONFLICT,
            AUDIT_TRIGGER_REFUSAL, UNIQUE_CONSTRAINT_VIOLATION, UNIQUE_INDEX_VIOLATION,
            VALUE_TOO_LONG, VALUE_CONVERSION_FAILED, DATA_TYPE_CONVERSION_FAILED,
            LOCK_NOT_AVAILABLE, STATEMENT_TIMEOUT, CONNECTION_FAILURE,
            CONNECTION_NOT_ESTABLISHED));

    /** The rows in order: the first one an SQL exception matches is its row. */
    private final List<EngineFailure> table;

    DatabaseFailureCategory(List<EngineFailure> table) {
        this.table = List.copyOf(table);
    }

    /** The category of {@code failure}: the database's, or {@code application}. */
    public static ErrorCategory of(Throwable failure) {
        return ENGINE.categoryOf(failure);
    }

    /**
     * Whether the database chose {@code failure}'s transaction as a deadlock's victim — the one
     * failure of the mapping's {@code database} rows that is retried, once, before it is an
     * {@code ERROR} (the deadlock retry, ADR 0013's as-13): an {@link SQLException} in the cause
     * chain whose row says so. SQL Server reports its victim (1205) under the serialization state
     * {@code 40001}, which it shares with the snapshot conflict (3960) that must not be retried,
     * so its rows key on the error number too.
     */
    public static boolean deadlock(Throwable failure) {
        return ENGINE.deadlockVictim(failure);
    }

    ErrorCategory categoryOf(Throwable failure) {
        boolean database = false;
        boolean beneathSql = false;
        for (Throwable link : CauseChain.of(failure)) {
            if (link instanceof PermissionDeniedDataAccessException
                    || beneathSql && trustFailure(link)) {
                return ErrorCategory.CERT_AUTH;
            }
            if (link instanceof SQLException sql) {
                if (row(sql).map(EngineFailure::category).orElse(null) == ErrorCategory.CERT_AUTH) {
                    return ErrorCategory.CERT_AUTH;
                }
                beneathSql = true;
            }
            database |= beneathSql
                    || link instanceof DataAccessException
                    || link instanceof TransactionException;
        }
        return database ? ErrorCategory.DATABASE : ErrorCategory.APPLICATION;
    }

    boolean deadlockVictim(Throwable failure) {
        for (Throwable link : CauseChain.of(failure)) {
            if (link instanceof SQLException sql && row(sql).filter(EngineFailure::deadlock).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private Optional<EngineFailure> row(SQLException sql) {
        return table.stream().filter(row -> row.reportedAs(sql)).findFirst();
    }

    private static boolean trustFailure(Throwable link) {
        return link instanceof SSLException || link instanceof CertificateException;
    }
}
