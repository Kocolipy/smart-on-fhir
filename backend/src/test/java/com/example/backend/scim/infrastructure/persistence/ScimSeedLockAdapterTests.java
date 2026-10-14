package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ScriptedJdbc;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seeding lock at the driver's boundary: what each status {@code sp_getapplock} answers
 * means for the start, and how long it is let wait. That a second seed really waits on the lock
 * is {@code ScimSeedIntegrationTests}', against the server.
 */
class ScimSeedLockAdapterTests {

    /** The lock batch, as far as the script needs to recognise it. */
    private static final String LOCK = "DECLARE @transaction_opened INT";

    private final ScriptedJdbc database = new ScriptedJdbc();

    private ScimSeedLockAdapter adapter() {
        return new ScimSeedLockAdapter(new JdbcTemplate(database.dataSource()));
    }

    /** {@code 0}: granted at once. */
    @Test
    void aLockGrantedAtOnceLetsSeedingGoAhead() {
        database.query(LOCK, 0);

        assertThatCode(adapter()::acquire).doesNotThrowAnyException();
    }

    /** {@code 1}: granted after waiting for another start's seeding. */
    @Test
    void aLockGrantedAfterWaitingLetsSeedingGoAhead() {
        database.query(LOCK, 1);

        assertThatCode(adapter()::acquire).doesNotThrowAnyException();
    }

    /**
     * {@code -1}: not granted within the wait. The start fails loudly, as a lock the database
     * would not give — a {@code database} failure to investigate in ADR 0015's mapping, not a fault
     * of the application's own — naming how long it waited.
     */
    @Test
    void aLockNotGrantedInTimeFailsTheStartAsADatabaseLockFailure() {
        database.query(LOCK, -1);

        assertThatThrownBy(adapter()::acquire)
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessage("the seeding lock was not granted within 60 s (sp_getapplock status -1)");
    }

    /** {@code -3}, a deadlock's victim, and every other refusal fail the start alike. */
    @Test
    void aLockRefusedAsADeadlocksVictimFailsTheStart() {
        database.query(LOCK, -3);

        assertThatThrownBy(adapter()::acquire).isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    void aStatusThatReadsNothingFailsTheStart() {
        database.query(LOCK, (Object) null);

        assertThatThrownBy(adapter()::acquire).isInstanceOf(CannotAcquireLockException.class);
    }

    /** The lock waits at most 60 s, in milliseconds as {@code sp_getapplock} takes it. */
    @Test
    void theLockWaitsAtMostSixtySeconds() {
        database.query(LOCK, 0);

        adapter().acquire();

        assertThat(database.executed()).singleElement()
                .extracting(ScriptedJdbc.Executed::arguments)
                .isEqualTo(List.of(60_000));
    }
}
