package com.example.backend.observability;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The text of a database error, removed before a record is written: what makes "the exception
 * attached" safe for a failure the database raised.
 *
 * <p>A database's error message quotes what it refused — the duplicate key, the truncated or
 * unconvertible value, the failing row — and names the schema, the table and sometimes the
 * database it refused it in. The exceptions that wrap it repeat it: Spring's translated
 * {@code DataAccessException} carries the driver's message and the statement, Hibernate's
 * {@code ConstraintViolationException} the same. So the redaction is keyed on the generic
 * {@link SQLException}, never on a driver's class, and covers three kinds of throwable in the
 * tree the record would print:
 *
 * <ul>
 *   <li>an {@link SQLException}: its message becomes {@link #redactedMessage};
 *   <li>a throwable with an {@link SQLException} anywhere beneath it in its cause chain: its
 *       message becomes the redacted message of the nearest one, because it quotes it;
 *   <li>anything else — a cause beneath the {@link SQLException}, such as the socket failure
 *       the driver reported — keeps its own message.
 * </ul>
 *
 * <p>Every class name and every frame is kept. The stand-in a redacted tree is built from
 * renders exactly as its original would, line for line, with the message replaced: its
 * {@code toString()} is the original's class name and the redacted message, which is what both
 * of the encoder's renderers print for each line ({@code Throwable::toString} in Boot's stack
 * trace printer, and logback's overriding message in its converter).
 *
 * <p>Applied by {@link EcsErrorFieldsCustomizer} to {@code error.message} and
 * {@code error.stack_trace} of every structured record — console and file alike — so it holds
 * for every call site, Spring's and the libraries' included, rather than for the ones that
 * remembered. See ADR 0003, "SQL exception text".
 */
final class SqlExceptionRedaction {

    private SqlExceptionRedaction() {
    }

    /**
     * What a record says in place of an {@link SQLException}'s message: {@code SQL Server error
     * <n>}, the server's error number, which identifies the failure and quotes nothing (ADR 0013,
     * "SQL exception text is redacted"). A failure the driver raised itself carries {@code 0}.
     */
    static String redactedMessage(SQLException failure) {
        return "SQL Server error " + failure.getErrorCode();
    }

    /**
     * {@code failure} as a record may print it: itself when nothing in its tree is an
     * {@link SQLException}, or else a stand-in tree with every quoting message redacted.
     */
    static Throwable redacted(Throwable failure) {
        return holdsSqlException(failure, identitySet()) ? standIn(failure, identitySet()) : failure;
    }

    /** Whether {@code failure}, its causes or anything suppressed in them is an SQL exception. */
    private static boolean holdsSqlException(@Nullable Throwable failure, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) {
            return false;
        }
        if (failure instanceof SQLException || holdsSqlException(failure.getCause(), seen)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (holdsSqlException(suppressed, seen)) {
                return true;
            }
        }
        return false;
    }

    /** The nearest {@link SQLException} at or beneath {@code failure} in its cause chain. */
    private static @Nullable SQLException nearestSqlException(Throwable failure) {
        for (Throwable link : CauseChain.of(failure)) {
            if (link instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /**
     * The stand-in for {@code original}, built cause-first. A throwable already in the tree —
     * a cyclic chain, or one suppressed twice — is cut rather than followed again.
     */
    private static Throwable standIn(Throwable original, Set<Throwable> built) {
        built.add(original);
        Throwable cause = original.getCause();
        Throwable causeStandIn = cause == null || built.contains(cause) ? null : standIn(cause, built);
        SQLException nearest = nearestSqlException(original);
        StandInException standIn = nearest == null
                ? new StandInException(original.getMessage(), original.toString(), causeStandIn)
                : StandInException.of(
                        original.getClass().getName(), redactedMessage(nearest), causeStandIn);
        standIn.setStackTrace(original.getStackTrace());
        for (Throwable suppressed : original.getSuppressed()) {
            if (!built.contains(suppressed)) {
                standIn.addSuppressed(standIn(suppressed, built));
            }
        }
        return standIn;
    }

    private static Set<Throwable> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * One throwable of a redacted tree: its original's frames, under the original's class name
     * and the message the record may carry. Never thrown.
     */
    static final class StandInException extends RuntimeException {

        private final String line;

        StandInException(@Nullable String message, String line, @Nullable Throwable cause) {
            super(message, cause, true, true);
            this.line = line;
        }

        static StandInException of(String className, String message, @Nullable Throwable cause) {
            return new StandInException(message, className + ": " + message, cause);
        }

        @Override
        public String toString() {
            return line;
        }
    }
}
