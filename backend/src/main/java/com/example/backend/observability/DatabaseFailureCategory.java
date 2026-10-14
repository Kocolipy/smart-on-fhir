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
 * Which {@code error.category} a failure the database raised belongs to — ADR 0015's mapping —
 * and whether it is a deadlock's victim, the one failure the deadlock retry repeats. Both are
 * answered from one table of the failures the engine reports, keyed on the SQLState of the
 * {@link SQLException} in the cause chain <em>and</em> its vendor error number
 * ({@link SQLException#getErrorCode()}), beside Spring's data-access and transaction hierarchy.
 * Never a driver's class: the SQL Server switchover changes the table's rows, not this code, and
 * a failure whose SQLState another shares is told apart by its error number.
 *
 * <ul>
 *   <li><strong>{@code cert/auth}</strong> — the database refused this service's own
 *       credential or rights, which needs its configuration checked rather than its code: a
 *       refused login (SQLState class {@code 28}), a permission refusal ({@code 42501}, or
 *       Spring's {@link PermissionDeniedDataAccessException}), and a TLS trust failure (an
 *       {@code SSLException} or {@code CertificateException} beneath the SQL exception).
 *   <li><strong>{@code database}</strong> — every other database failure: a deadlock or a
 *       serialization conflict, an untranslated unique violation, a truncation or a conversion
 *       error, a lock not acquired or a statement timed out, the audit table's append-only
 *       trigger refusing a change (its own {@code 27000}, ADR 0004's 2026-10-14 addendum), and
 *       any failure Spring's data access or transaction layer raised with no SQL exception
 *       beneath it.
 *   <li><strong>{@code application}</strong> — anything else: this is not a database failure.
 * </ul>
 */
public final class DatabaseFailureCategory {

    /**
     * One row of the table: a failure as the engine reports it, and what the mapping makes of
     * it. {@code sqlState} is a five-character state or a two-character class, which matches
     * every state in it; {@code errorCode} is the vendor error number. Either is {@code null}
     * for any: PostgreSQL reports no error number, so its rows name none.
     */
    record EngineFailure(String sqlState, Integer errorCode, ErrorCategory category,
            boolean deadlock) {

        boolean reportedAs(SQLException sql) {
            String state = sql.getSQLState();
            return (sqlState == null || state != null && state.startsWith(sqlState))
                    && (errorCode == null || errorCode == sql.getErrorCode());
        }
    }

    /** SQLState class {@code 28}, invalid authorization specification: a refused login. */
    static final EngineFailure REFUSED_LOGIN =
            new EngineFailure("28", null, ErrorCategory.CERT_AUTH, false);

    /**
     * SQL Server error {@code 229}, a permission denied on an object: a permission refusal.
     * Re-keyed with the switchover's schema (#68), because the append-only guarantees are asserted
     * by it; the rows still keyed on PostgreSQL's SQLStates are re-keyed with the error mapping
     * (#69).
     */
    static final EngineFailure PERMISSION_DENIED =
            new EngineFailure(null, 229, ErrorCategory.CERT_AUTH, false);

    /** SQLState {@code 40P01}, PostgreSQL's {@code deadlock_detected}: the victim, retried. */
    static final EngineFailure DEADLOCK_VICTIM =
            new EngineFailure("40P01", null, ErrorCategory.DATABASE, true);

    /**
     * SQLState {@code 40001}, serialization failure: a conflict between concurrent transactions
     * that is no deadlock, and is not retried. SQL Server reports its snapshot conflict (3960)
     * under this state, and its deadlock victim (1205) too, so the switchover keys this row and
     * the victim's on the error number.
     */
    static final EngineFailure SERIALIZATION_FAILURE =
            new EngineFailure("40001", null, ErrorCategory.DATABASE, false);

    /**
     * Error {@code 50001}, the number the audit table's append-only trigger throws (V1): the
     * trigger refusing a change, never a permission refusal (ADR 0004's 2026-10-14 addendum).
     * Re-keyed with the switchover's schema (#68), as {@link #PERMISSION_DENIED} is.
     */
    static final EngineFailure AUDIT_TRIGGER_REFUSAL =
            new EngineFailure(null, 50001, ErrorCategory.DATABASE, false);

    /** SQLState {@code 23505}: a unique violation no adapter translated. */
    static final EngineFailure UNIQUE_VIOLATION =
            new EngineFailure("23505", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 22001}, string data right truncation: a value too long for its column. */
    static final EngineFailure VALUE_TOO_LONG =
            new EngineFailure("22001", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 22P02}: a value that does not convert to its column's type. */
    static final EngineFailure INVALID_TEXT_REPRESENTATION =
            new EngineFailure("22P02", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 55P03}: a lock asked for without waiting, and held elsewhere. */
    static final EngineFailure LOCK_NOT_AVAILABLE =
            new EngineFailure("55P03", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 57014}, query canceled: a statement cancelled by its timeout. */
    static final EngineFailure STATEMENT_TIMEOUT =
            new EngineFailure("57014", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 08006}: an open connection lost. */
    static final EngineFailure CONNECTION_FAILURE =
            new EngineFailure("08006", null, ErrorCategory.DATABASE, false);

    /** SQLState {@code 08001}: a connection the driver could not open. */
    static final EngineFailure CONNECTION_NOT_ESTABLISHED =
            new EngineFailure("08001", null, ErrorCategory.DATABASE, false);

    /**
     * Today's engine, PostgreSQL. A state with no row here is a {@code database} failure, so the
     * {@code database} rows that are no deadlock change no answer on PostgreSQL: they name, once,
     * the engine's codes for the failures the service meets, which the switchover re-keys.
     */
    private static final DatabaseFailureCategory ENGINE = new DatabaseFailureCategory(List.of(
            REFUSED_LOGIN, PERMISSION_DENIED, DEADLOCK_VICTIM, SERIALIZATION_FAILURE,
            AUDIT_TRIGGER_REFUSAL, UNIQUE_VIOLATION, VALUE_TOO_LONG, INVALID_TEXT_REPRESENTATION,
            LOCK_NOT_AVAILABLE, STATEMENT_TIMEOUT, CONNECTION_FAILURE, CONNECTION_NOT_ESTABLISHED));

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
     * {@code ERROR} (the deadlock retry, ADR 0015's as-13): an {@link SQLException} in the cause
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
