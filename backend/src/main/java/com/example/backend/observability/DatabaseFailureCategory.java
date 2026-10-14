package com.example.backend.observability;

import com.example.backend.observability.LogEvent.ErrorCategory;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import javax.net.ssl.SSLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Which {@code error.category} a failure the database raised belongs to — ADR 0015's mapping,
 * keyed on what every engine reports: the SQLState of the {@link SQLException} in the
 * cause chain, and Spring's data-access and transaction hierarchy. Never a driver's class, so
 * the mapping survives a change of engine; the SQL Server switchover adds its error numbers
 * where a state does not tell two failures apart.
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

    /** SQLState class {@code 28}: invalid authorization specification — a refused login. */
    private static final String INVALID_AUTHORIZATION_CLASS = "28";

    /** SQLState {@code 42501}: insufficient privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLState {@code 40P01}: PostgreSQL's {@code deadlock_detected}. */
    private static final String DEADLOCK_DETECTED = "40P01";

    private DatabaseFailureCategory() {
    }

    /** The category of {@code failure}: the database's, or {@code application}. */
    public static ErrorCategory of(Throwable failure) {
        boolean database = false;
        boolean beneathSql = false;
        for (Throwable link : CauseChain.of(failure)) {
            if (link instanceof PermissionDeniedDataAccessException
                    || beneathSql && trustFailure(link)) {
                return ErrorCategory.CERT_AUTH;
            }
            if (link instanceof SQLException sql) {
                if (refusedCredential(sql.getSQLState())) {
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

    /**
     * Whether the database chose {@code failure}'s transaction as a deadlock's victim — the one
     * failure of the mapping's {@code database} rows that is retried, once, before it is an
     * {@code ERROR} (the deadlock retry, ADR 0015's as-13). Keyed on the SQLState of the
     * {@link SQLException} in the cause chain: PostgreSQL's {@code deadlock_detected}. SQL Server
     * reports its victim (1205) under the serialization state {@code 40001}, which it shares with
     * the snapshot conflict that must not be retried, so the switchover adds its error number
     * here.
     */
    public static boolean deadlock(Throwable failure) {
        for (Throwable link : CauseChain.of(failure)) {
            if (link instanceof SQLException sql && DEADLOCK_DETECTED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean refusedCredential(String state) {
        return state != null && (state.startsWith(INVALID_AUTHORIZATION_CLASS)
                || state.equals(INSUFFICIENT_PRIVILEGE));
    }

    private static boolean trustFailure(Throwable link) {
        return link instanceof SSLException || link instanceof CertificateException;
    }
}
