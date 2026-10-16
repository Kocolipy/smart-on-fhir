package com.example.backend.observability;

import com.example.backend.observability.DatabaseFailureCategory.EngineFailure;
import java.sql.SQLException;

/**
 * The engine's failures by name, as its driver raises them, so no test restates an error number.
 * Each is built from its row in {@link DatabaseFailureCategory}'s table, the one place the
 * engine's codes are written. A row that names no error number — a failure the driver raised
 * itself — is raised with {@code 0}, as the driver does.
 */
public final class SqlFailures {

    /** The victim the database chose to break a deadlock. */
    public static final Failure DEADLOCK_VICTIM = of(DatabaseFailureCategory.DEADLOCK_VICTIM);

    /** A snapshot update conflict: never a deadlock, and never retried. */
    public static final Failure SNAPSHOT_CONFLICT = of(DatabaseFailureCategory.SNAPSHOT_CONFLICT);

    /** A privilege this service's login does not hold. */
    public static final Failure PERMISSION_DENIED = of(DatabaseFailureCategory.PERMISSION_DENIED);

    /** The audit table's append-only trigger refusing a change. */
    public static final Failure AUDIT_TRIGGER_REFUSAL =
            of(DatabaseFailureCategory.AUDIT_TRIGGER_REFUSAL);

    /** A login the database refused: this service's own credential. */
    public static final Failure LOGIN_FAILED = of(DatabaseFailureCategory.REFUSED_LOGIN);

    /** A unique constraint no adapter translated. */
    public static final Failure UNIQUE_CONSTRAINT_VIOLATION =
            of(DatabaseFailureCategory.UNIQUE_CONSTRAINT_VIOLATION);

    /** A value too long for its column. */
    public static final Failure VALUE_TOO_LONG = of(DatabaseFailureCategory.VALUE_TOO_LONG);

    /** A value that does not convert to the type it is compared or stored as. */
    public static final Failure VALUE_CONVERSION_FAILED =
            of(DatabaseFailureCategory.VALUE_CONVERSION_FAILED);

    /** A lock request whose time-out ran out. */
    public static final Failure LOCK_NOT_AVAILABLE = of(DatabaseFailureCategory.LOCK_NOT_AVAILABLE);

    /** A statement cancelled by its timeout. */
    public static final Failure STATEMENT_TIMEOUT = of(DatabaseFailureCategory.STATEMENT_TIMEOUT);

    /** An open connection lost. */
    public static final Failure CONNECTION_FAILURE = of(DatabaseFailureCategory.CONNECTION_FAILURE);

    /** A connection the driver could not open. */
    public static final Failure CONNECTION_NOT_ESTABLISHED =
            of(DatabaseFailureCategory.CONNECTION_NOT_ESTABLISHED);

    /** The SQLState SQL Server's driver reports for a server error raised with state 1. */
    private static final String SQL_SERVER_STATE = "S0001";

    private SqlFailures() {
    }

    /** A failure as the engine reports it: its SQLState and its vendor error number. */
    public record Failure(String sqlState, int errorCode) {

        public SQLException raised() {
            return raised("driver");
        }

        public SQLException raised(String message) {
            return new SQLException(message, sqlState, errorCode);
        }

        public SQLException raised(String message, Throwable cause) {
            return new SQLException(message, sqlState, errorCode, cause);
        }

        /** The whole of the redacted text the log writes for this failure in place of the driver's. */
        public String redacted() {
            return "SQL Server error " + errorCode;
        }
    }

    /**
     * A row that names no SQLState keys on its error number alone — the driver derives the state
     * from the error's own state number — so it is raised with {@value #SQL_SERVER_STATE}, a
     * state the driver reports and the row does not consult.
     */
    private static Failure of(EngineFailure row) {
        return new Failure(row.sqlState() == null ? SQL_SERVER_STATE : row.sqlState(),
                row.errorCode() == null ? 0 : row.errorCode());
    }
}
