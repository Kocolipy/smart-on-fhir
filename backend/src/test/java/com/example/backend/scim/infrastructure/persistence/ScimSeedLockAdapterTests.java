package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ScriptedJdbc;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seeding lock at the driver's boundary: what each answer the lock statement gives means for
 * the start, and how long it is let wait. That a second seed really waits on the row is
 * {@code ScimSeedIntegrationTests}', against the server.
 */
class ScimSeedLockAdapterTests {

    /** The lock statement, as far as the script needs to recognise it. */
    private static final String LOCK = "SELECT lock_name FROM scim_seed_locks WITH";

    private final ScriptedJdbc database = new ScriptedJdbc();

    private ScimSeedLockAdapter adapter() {
        return new ScimSeedLockAdapter(new JdbcTemplate(database.dataSource()));
    }

    /** The row comes back: this transaction holds its lock, and seeding goes ahead. */
    @Test
    void theSeedRowReturnedLetsSeedingGoAhead() {
        database.query(LOCK, "ScimSeed");

        assertThatCode(adapter()::acquire).doesNotThrowAnyException();
    }

    @Test
    void theLockStatementAsksForTheSeedRow() {
        database.query(LOCK, "ScimSeed");

        adapter().acquire();

        assertThat(database.executed()).singleElement()
                .extracting(ScriptedJdbc.Executed::arguments)
                .isEqualTo(List.of("ScimSeed"));
    }

    /**
     * No row at all: the statement waits on a held row rather than reading past it, so nothing
     * returned means the migration that inserts the row has not run. The start fails loudly
     * rather than seed under a lock that locks nothing.
     */
    @Test
    void aMissingSeedRowFailsTheStartNamingTheMigration() {
        database.query(LOCK);

        assertThatThrownBy(adapter()::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No seeding lock row exists in scim_seed_locks;"
                        + " the migration that creates it has not run.");
    }

    /**
     * The driver cancels the wait after 60 s. The start fails as a lock the database would not
     * give — a {@code database} failure to investigate in ADR 0015's mapping — naming how long it
     * waited.
     */
    @Test
    void aLockNotGrantedInTimeFailsTheStartAsADatabaseLockFailure() {
        SQLTimeoutException timedOut = new SQLTimeoutException("The query has timed out.", "HY008");
        database.failing(LOCK, timedOut);

        assertThatThrownBy(adapter()::acquire)
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessage("the seeding lock was not granted within 60 s")
                .hasRootCause(timedOut);
    }

    /** Any other failure of the statement is the database's own, and is not taken for a timeout. */
    @Test
    void anotherFailureOfTheLockStatementIsNotReportedAsATimeout() {
        database.failing(LOCK, new SQLException("Invalid object name 'scim_seed_locks'.", "S0002", 208));

        assertThatThrownBy(adapter()::acquire)
                .isNotInstanceOf(CannotAcquireLockException.class)
                .hasMessageNotContaining("not granted");
    }

    /** The lock waits at most 60 s (ADR 0015, "Bounded timeouts"). */
    @Test
    void theLockWaitsAtMostSixtySeconds() {
        database.query(LOCK, "ScimSeed");

        adapter().acquire();

        assertThat(database.executed()).singleElement()
                .extracting(ScriptedJdbc.Executed::queryTimeoutSeconds)
                .isEqualTo(60);
    }
}
