package com.example.backend.scim.infrastructure.persistence;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Lands another transaction's commit between two statements of a read, every time, so a test can
 * show what a read does when a concurrent write lands mid-read without relying on timing.
 *
 * <p>Hibernate calls this inspector as it prepares each statement, before the statement runs.
 * {@link #afterReading} arms it on the calling thread only: once a statement reading the named
 * table has been prepared, the inspector runs the commit, on another thread and so on its own
 * connection, to completion before the next statement is prepared. Under READ COMMITTED that next
 * statement sees the commit. A read that finishes in one statement never reaches the commit.
 *
 * <p>The test {@code application.yaml} names this class as
 * {@code hibernate.session_factory.statement_inspector} for every context. It sits there rather
 * than in a test configuration of its own so that the tests using it share a context with every
 * other class. Unarmed it returns each statement unchanged and does nothing else.
 */
public final class CommitBetweenStatements implements StatementInspector {

    private static final ThreadLocal<Armed> ARMED = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        Armed armed = ARMED.get();
        if (armed != null) {
            armed.beforeStatement(sql);
        }
        return sql;
    }

    /**
     * Runs {@code read} on this thread. Once {@code read} has prepared a statement selecting from
     * {@code table}, {@code commit} runs and completes before {@code read}'s next statement.
     * {@code commit} runs at most once.
     */
    public static <T> T afterReading(String table, Runnable commit, Supplier<T> read) {
        ARMED.set(new Armed(Pattern.compile("\\bfrom\\s+" + Pattern.quote(table) + "\\b"), commit));
        try {
            return read.get();
        } finally {
            ARMED.remove();
        }
    }

    private static final class Armed {

        private final Pattern readsTable;
        private final Runnable commit;
        private boolean tableRead;
        private boolean committed;

        Armed(Pattern readsTable, Runnable commit) {
            this.readsTable = readsTable;
            this.commit = commit;
        }

        void beforeStatement(String sql) {
            if (tableRead && !committed) {
                committed = true;
                CompletableFuture.runAsync(commit).join();
            }
            if (readsTable.matcher(sql).find()) {
                tableRead = true;
            }
        }
    }
}
