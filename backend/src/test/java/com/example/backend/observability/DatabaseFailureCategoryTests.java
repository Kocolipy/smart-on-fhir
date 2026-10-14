package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.LogEvent.ErrorCategory;
import java.net.ConnectException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * ADR 0015's category mapping, keyed on what is engine-neutral — the SQLState of the
 * {@link SQLException} in the chain, and Spring's data-access hierarchy — never a driver's class.
 */
class DatabaseFailureCategoryTests {

    private static ErrorCategory category(Throwable failure) {
        return DatabaseFailureCategory.of(failure);
    }

    private static DataIntegrityViolationException translated(String state) {
        return new DataIntegrityViolationException("translated", new SQLException("driver", state));
    }

    // ---- database ----------------------------------------------------------------------------

    @Test
    void aDeadlockIsADatabaseFailure() {
        assertThat(category(translated("40P01"))).isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void aSerializationOrSnapshotConflictIsADatabaseFailure() {
        assertThat(category(translated("40001"))).isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void anUntranslatedUniqueViolationIsADatabaseFailure() {
        assertThat(category(translated("23505"))).isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void aTruncationIsADatabaseFailure() {
        assertThat(category(translated("22001"))).isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void aConversionErrorIsADatabaseFailure() {
        assertThat(category(translated("22P02"))).isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void aLockThatCouldNotBeAcquiredIsADatabaseFailure() {
        assertThat(category(translated("55P03"))).isEqualTo(ErrorCategory.DATABASE);
    }

    @Test
    void aStatementTimeoutIsADatabaseFailure() {
        assertThat(category(translated("57014"))).isEqualTo(ErrorCategory.DATABASE);
    }

    /**
     * The audit table's append-only trigger refusing a change raises the standard's triggered
     * data change violation, never a permission refusal's state (ADR 0004's 2026-10-14 addendum).
     */
    @Test
    void anAuditTriggerRefusalIsADatabaseFailure() {
        assertThat(category(new UncategorizedSQLException("delete", "DELETE",
                new SQLException("driver", "27000")))).isEqualTo(ErrorCategory.DATABASE);
    }

    /** A raw SQL exception under a non-Spring wrapper is still the database's. */
    @Test
    void anSqlExceptionUnderAnyWrapperIsADatabaseFailure() {
        assertThat(category(new IllegalStateException("wrapped", new SQLException("x", "23505"))))
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

    @Test
    void aRefusedDatabaseLoginIsACertAuthFailure() {
        assertThat(category(new CannotGetJdbcConnectionException("no connection",
                new SQLException("driver", "28P01")))).isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void anyInvalidAuthorizationStateIsACertAuthFailure() {
        assertThat(category(new SQLException("driver", "28000"))).isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void aPermissionRefusalIsACertAuthFailure() {
        assertThat(category(translated("42501"))).isEqualTo(ErrorCategory.CERT_AUTH);
    }

    /** Spring's own permission refusal, whatever the cause it carries. */
    @Test
    void springsPermissionDeniedIsACertAuthFailure() {
        assertThat(category(new PermissionDeniedDataAccessException("refused", null)))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void aTlsHandshakeFailureBeneathTheConnectionIsACertAuthFailure() {
        assertThat(category(new CannotCreateTransactionException("down",
                new SQLException("driver", "08006", new SSLHandshakeException("untrusted")))))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    @Test
    void anUntrustedCertificateBeneathTheConnectionIsACertAuthFailure() {
        assertThat(category(new SQLException("driver", "08006",
                new IllegalStateException("wrapped", new CertificateException("untrusted")))))
                .isEqualTo(ErrorCategory.CERT_AUTH);
    }

    /** A connection the driver could not open for any reason but trust is the database's. */
    @Test
    void aConnectionFailureBeneathTheSqlExceptionThatIsNotTlsIsADatabaseFailure() {
        assertThat(category(new SQLException("driver", "08001",
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
        assertThat(DatabaseFailureCategory.deadlock(translated("40P01"))).isTrue();
    }

    /** A serialization or snapshot conflict is the mapping's {@code ERROR}, never retried. */
    @Test
    void aSerializationConflictIsNotADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(translated("40001"))).isFalse();
    }

    @Test
    void aFailureWithNoSqlExceptionIsNotADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void aDeadlockAnywhereInTheChainIsADeadlock() {
        assertThat(DatabaseFailureCategory.deadlock(new IllegalStateException("wrapped",
                new IllegalStateException("again", new SQLException("x", "40P01"))))).isTrue();
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
