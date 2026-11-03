package com.example.backend.observability;

import static com.example.backend.observability.SqlFailures.AUDIT_TRIGGER_REFUSAL;
import static com.example.backend.observability.SqlFailures.CONNECTION_FAILURE;
import static com.example.backend.observability.SqlFailures.CONNECTION_NOT_ESTABLISHED;
import static com.example.backend.observability.SqlFailures.DEADLOCK_VICTIM;
import static com.example.backend.observability.SqlFailures.VALUE_CONVERSION_FAILED;
import static com.example.backend.observability.SqlFailures.LOCK_NOT_AVAILABLE;
import static com.example.backend.observability.SqlFailures.PERMISSION_DENIED;
import static com.example.backend.observability.SqlFailures.LOGIN_FAILED;
import static com.example.backend.observability.SqlFailures.SNAPSHOT_CONFLICT;
import static com.example.backend.observability.SqlFailures.STATEMENT_TIMEOUT;
import static com.example.backend.observability.SqlFailures.UNIQUE_CONSTRAINT_VIOLATION;
import static com.example.backend.observability.SqlFailures.VALUE_TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.DatabaseFailureCategory.EngineFailure;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.net.ConnectException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * ADR 0013's category mapping and the deadlock it retries, keyed on what the engine reports — the
 * SQLState and vendor error number of the {@link SQLException} in the chain, and Spring's
 * data-access hierarchy — never a driver's class. The engine's failures are raised by name
 * from {@link SqlFailures}; the table's key is proven against tables of the test's own.
 */
class DatabaseFailureCategoryTests {

    private static ErrorCategory category(Throwable failure) {
        return DatabaseFailureCategory.of(failure);
    }

    private static DataIntegrityViolationException translated(SqlFailures.Failure failure) {
        return new DataIntegrityViolationException("translated", failure.raised());
    }

    // ---- database ----------------------------------------------------------------------------

    /** The engine's own failures, translated by Spring as it finds them, are the database's. */
    static Stream<Arguments> translatedDatabaseFailures() {
        return Stream.of(
                Arguments.of("a deadlock", DEADLOCK_VICTIM),
                Arguments.of("a serialization or snapshot conflict", SNAPSHOT_CONFLICT),
                Arguments.of("an untranslated unique violation", UNIQUE_CONSTRAINT_VIOLATION),
                Arguments.of("a truncation", VALUE_TOO_LONG),
                Arguments.of("a conversion error", VALUE_CONVERSION_FAILED),
                Arguments.of("a lock that could not be acquired", LOCK_NOT_AVAILABLE),
                Arguments.of("a statement timeout", STATEMENT_TIMEOUT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("translatedDatabaseFailures")
    void aTranslatedEngineFailureIsADatabaseFailure(String name, SqlFailures.Failure failure) {
        assertThat(category(translated(failure))).isEqualTo(ErrorCategory.DATABASE);
    }

    /**
     * The audit table's append-only trigger refusing a change raises the standard's triggered
     * data change violation, never a permission refusal's state (ADR 0004).
     */
    @Test
    void anAuditTriggerRefusalIsADatabaseFailure() {
        assertThat(category(new UncategorizedSQLException("delete", "DELETE",
                AUDIT_TRIGGER_REFUSAL.raised()))).isEqualTo(ErrorCategory.DATABASE);
    }

    /** A raw SQL exception under a non-Spring wrapper is still the database's. */
    @Test
    void anSqlExceptionUnderAnyWrapperIsADatabaseFailure() {
        assertThat(category(
                new IllegalStateException("wrapped", UNIQUE_CONSTRAINT_VIOLATION.raised())))
                .isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void anSqlExceptionWithNoStateIsADatabaseFailure() {
        assertThat(category(new SQLException("x"))).isEqualTo(ErrorCategory.DATABASE);
    }

    /** Spring's data-access and transaction layers raise database failures with no SQL cause too. */
    @Test
    void aDataAccessFailureWithNoSqlCauseIsADatabaseFailure() {
        assertThat(category(new DataIntegrityViolationException("refused")))
                .isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void aTransactionFailureWithNoSqlCauseIsADatabaseFailure() {
        assertThat(category(new CannotCreateTransactionException("down")))
                .isEqualTo(ErrorCategory.DATABASE);
    }

    // ---- cert/auth ---------------------------------------------------------------------------

    /**
     * A refused login and a refused permission are this service's own rights: a refused database
     * login, the engine's permission refusal, and Spring's own whatever the cause it carries.
     */
    static Stream<Arguments> refusedRights() {
        return Stream.of(
                Arguments.of("a refused database login",
                        new CannotGetJdbcConnectionException("no connection", LOGIN_FAILED.raised())),
                Arguments.of("a permission refusal", translated(PERMISSION_DENIED)),
                Arguments.of("Spring's permission denied",
                        new PermissionDeniedDataAccessException("refused", null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedRights")
    void aRefusalOfTheServicesRightsIsACertAuthFailure(String name, Throwable failure) {
        assertThat(category(failure)).isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void aTlsHandshakeFailureBeneathTheConnectionIsACertAuthFailure() {
        assertThat(category(new CannotCreateTransactionException("down",
                CONNECTION_FAILURE.raised("driver", new SSLHandshakeException("untrusted")))))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void anUntrustedCertificateBeneathTheConnectionIsACertAuthFailure() {
        assertThat(category(CONNECTION_FAILURE.raised("driver",
                new IllegalStateException("wrapped", new CertificateException("untrusted")))))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    /** A connection the driver could not open for any reason but trust is the database's. */
    @Test
    void aConnectionFailureBeneathTheSqlExceptionThatIsNotTlsIsADatabaseFailure() {
        assertThat(category(CONNECTION_NOT_ESTABLISHED.raised("driver",
                new ConnectException("refused")))).isEqualTo(ErrorCategory.DATABASE);
    }

    // ---- not the database's ------------------------------------------------------------------

    @Test
    void aFailureWithNoDatabaseInItIsAnApplicationFailure() {
        assertThat(category(new IllegalStateException("bug"))).isEqualTo(ErrorCategory.APPLICATION);
    }

    /** A TLS failure that is not the database's is not classified as the database's. */
    @Test
    void aTlsFailureOutsideAnyDatabaseCallIsAnApplicationFailure() {
        assertThat(category(new IllegalStateException("x", new SSLHandshakeException("untrusted"))))
                .isEqualTo(ErrorCategory.APPLICATION);
    }

    // ---- the deadlock the retry repeats ----------------------------------------------------

    @Test
    void aDeadlockVictimIsADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(translated(DEADLOCK_VICTIM))).isTrue();
    }

    /** A serialization or snapshot conflict is the mapping's {@code ERROR}, never retried. */
    @Test
    void aSerializationConflictIsNotADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(translated(SNAPSHOT_CONFLICT))).isFalse();
    }

    @Test
    void aFailureWithNoSqlExceptionIsNotADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void aDeadlockAnywhereInTheChainIsADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(new IllegalStateException("wrapped",
                new IllegalStateException("again", DEADLOCK_VICTIM.raised())))).isTrue();
    }

    // ---- SQL Server, as its driver reports each failure ------------------------------------
    //
    // Literals rather than SqlFailures: what mssql-jdbc raises for each failure of ADR 0013's
    // mapping — the SQLState it derives and the server's error number — written independently of
    // the table under test.

    /** A deadlock's victim: error 1205, under the serialization state. */
    @Test
    void sqlServersDeadlockVictimIsTheDeadlockTheRetryRepeats() {
        assertThat(DatabaseFailureCategory.deadlock(
                new SQLException("chosen as the deadlock victim", "40001", 1205))).isTrue();
    }

    /** A snapshot update conflict (3960) shares the victim's state, and is never retried. */
    @Test
    void sqlServersSnapshotConflictIsNotADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(
                new SQLException("snapshot isolation update conflict", "40001", 3960))).isFalse();
    }

    @Test
    void sqlServersSnapshotConflictIsADatabaseFailure() {
        assertThat(category(new SQLException("snapshot conflict", "40001", 3960)))
                .isEqualTo(ErrorCategory.DATABASE);
    }

    /** SQLState {@code 40P01} names no failure SQL Server reports, so nothing retries on it. */
    @Test
    void aDeadlockVictimStateWithoutError1205IsNoDeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(new SQLException("x", "40P01", 0))).isFalse();
    }

    /** Error 18456, a login the server refused, reported under the generic state {@code S0001}. */
    @Test
    void sqlServersRefusedLoginIsACertAuthFailure() {
        assertThat(category(new CannotGetJdbcConnectionException("no connection",
                new SQLException("Login failed for user", "S0001", 18456))))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    /** Error 229, a permission denied on an object, whatever state the driver derives for it. */
    @Test
    void sqlServersPermissionDeniedIsACertAuthFailure() {
        assertThat(category(new SQLException("The SELECT permission was denied", "S0005", 229)))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    /** Each of the mapping's {@code database} rows, by the error number the server reports. */
    @Test
    void sqlServersOtherMappedFailuresAreDatabaseFailures() {
        assertThat(List.of(
                new SQLException("unique constraint", "23000", 2627),
                new SQLException("unique index", "23000", 2601),
                new SQLException("truncated", "S0001", 2628),
                new SQLException("conversion", "S0001", 245),
                new SQLException("conversion", "S0001", 8114),
                new SQLException("lock request time out", "S0001", 1222),
                new SQLException("the audit trigger refused", "S0001", 50001),
                new SQLException("The query has timed out.", "HY008", 0)))
                .extracting(DatabaseFailureCategory::of)
                .containsOnly(ErrorCategory.DATABASE);
    }

    /**
     * The refused login's state, {@code S0001}, is the one the driver derives for most server
     * errors, so only the number makes a failure a refused login.
     */
    @Test
    void anotherErrorUnderTheRefusedLoginsStateIsADatabaseFailure() {
        assertThat(category(new SQLException("Invalid column name", "S0001", 207)))
                .isEqualTo(ErrorCategory.DATABASE);
    }

    // ---- the table's key ---------------------------------------------------------------------

    /** The serialization state, which SQL Server's victim (1205) and snapshot conflict (3960) share. */
    private static final String SHARED = DEADLOCK_VICTIM.sqlState();

    /**
     * A table of the test's own: one SQLState shared by a deadlock
     * victim and a failure that must not be retried, told apart by the vendor error number.
     */
    private static final DatabaseFailureCategory SHARED_STATE = new DatabaseFailureCategory(List.of(
            new EngineFailure(SHARED, 1205, ErrorCategory.DATABASE, true)));

    @Test
    void aRowKeyedOnAVendorNumberMatchesTheStateUnderThatNumber() {
        assertThat(SHARED_STATE.deadlockVictim(new SQLException("x", SHARED, 1205))).isTrue();
    }

    @Test
    void aRowKeyedOnAVendorNumberDoesNotMatchTheStateUnderAnotherNumber() {
        assertThat(SHARED_STATE.deadlockVictim(new SQLException("x", SHARED, 3960))).isFalse();
    }

    /**
     * A row that names a state as well as a number matches only both: the victim's number under
     * another state is not the victim — so the snapshot conflict's state alone, or a number
     * reused under a state of its own, never retries a write.
     */
    @Test
    void aRowKeyedOnAStateDoesNotMatchItsNumberUnderAnotherState() {
        assertThat(SHARED_STATE.deadlockVictim(new SQLException("x", "S0001", 1205))).isFalse();
    }

    /** A row that names no SQLState matches the vendor number under any state, or none. */
    @Test
    void aRowWithNoStateMatchesItsVendorNumberAlone() {
        DatabaseFailureCategory table = new DatabaseFailureCategory(List.of(
                new EngineFailure(null, 18456, ErrorCategory.CERT_AUTH, false)));

        assertThat(table.categoryOf(new SQLException("x", (String) null, 18456)))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void aRowWithNoStateDoesNotMatchAnotherVendorNumber() {
        DatabaseFailureCategory table = new DatabaseFailureCategory(List.of(
                new EngineFailure(null, 18456, ErrorCategory.CERT_AUTH, false)));

        assertThat(table.categoryOf(new SQLException("x", "S0001", 229)))
                .isEqualTo(ErrorCategory.DATABASE);
    }

    /** The first row that matches decides, so a narrower row is written before a broader one. */
    @Test
    void theFirstMatchingRowDecides() {
        DatabaseFailureCategory table = new DatabaseFailureCategory(List.of(
                new EngineFailure("40", null, ErrorCategory.DATABASE, false),
                new EngineFailure(SHARED, 1205, ErrorCategory.DATABASE, true)));

        assertThat(table.deadlockVictim(new SQLException("x", SHARED, 1205))).isFalse();
    }

    /** A cyclic cause chain ends the walk rather than looping. */
    @Test
    void aCyclicChainIsClassifiedAndEnds() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);

        assertThat(category(first)).isEqualTo(ErrorCategory.APPLICATION);
    }
}
