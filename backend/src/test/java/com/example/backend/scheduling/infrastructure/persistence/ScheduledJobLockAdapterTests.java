package com.example.backend.scheduling.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ScriptedJdbc;
import com.example.backend.scheduling.domain.ScheduledJob;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scheduled-job lock adapter at the driver's boundary: what it makes of each answer the
 * lock statement and its follow-up give, and how long it lets either wait. That the statement
 * really reads past a held row is {@code ScheduledJobRunIntegrationTests}', against the server.
 */
class ScheduledJobLockAdapterTests {

    /** The lock statement, as far as the script needs to recognise it. */
    private static final String LOCK = "SELECT job_name FROM scheduled_job_locks WITH";

    /** The follow-up that tells a held row from a missing one. */
    private static final String COUNT = "SELECT count(*) FROM scheduled_job_locks";

    private final ScriptedJdbc database = new ScriptedJdbc();

    private ScheduledJobLockAdapter adapter() {
        return new ScheduledJobLockAdapter(new JdbcTemplate(database.dataSource()));
    }

    @Test
    void aRowTheLockStatementReturnsIsTheRunsToGoAheadWith() {
        database.query(LOCK, "dormancy");

        assertThat(adapter().tryAcquire(ScheduledJob.DORMANCY)).isTrue();
    }

    @Test
    void theLockStatementAsksForTheJobsOwnRow() {
        database.query(LOCK, "audit-retention");

        adapter().tryAcquire(ScheduledJob.AUDIT_RETENTION);

        assertThat(database.executed()).singleElement()
                .extracting(ScriptedJdbc.Executed::arguments)
                .isEqualTo(java.util.List.of("audit-retention"));
    }

    /** Read past, and still there: another run holds it, and this one skips. */
    @Test
    void aRowReadPastThatExistsIsHeldElsewhere() {
        database.query(LOCK).query(COUNT, 1);

        assertThat(adapter().tryAcquire(ScheduledJob.DORMANCY)).isFalse();
    }

    /** The follow-up counts the job's own row, not the table's. */
    @Test
    void theFollowUpCountsTheJobsOwnRow() {
        database.query(LOCK).query(COUNT, 1);

        adapter().tryAcquire(ScheduledJob.DORMANCY);

        assertThat(database.executed()).extracting(ScriptedJdbc.Executed::arguments)
                .containsExactly(java.util.List.of("dormancy"), java.util.List.of("dormancy"));
    }

    /**
     * Read past, and not there at all: the migration that inserts the row has not run, and the
     * run fails loudly rather than reading as "someone else holds it" forever.
     */
    @Test
    void aMissingRowFailsTheRunNamingTheJob() {
        database.query(LOCK).query(COUNT, 0);

        assertThatThrownBy(() -> adapter().tryAcquire(ScheduledJob.DORMANCY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No lock row exists for scheduled job dormancy;"
                        + " the migration that creates it has not run.");
    }

    @Test
    void aCountThatReadsNothingFailsTheRunToo() {
        database.query(LOCK).query(COUNT, (Object) null);

        assertThatThrownBy(() -> adapter().tryAcquire(ScheduledJob.AUDIT_RETENTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit-retention");
    }

    /**
     * Neither statement waits longer than 60 s (ADR 0013, "Bounded timeouts"): a lock that
     * cannot be read past — a table lock another session holds — fails the run rather than
     * holding its thread for ever.
     */
    @Test
    void eachStatementTimesOutAfterSixtySeconds() {
        database.query(LOCK).query(COUNT, 1);

        adapter().tryAcquire(ScheduledJob.DORMANCY);

        assertThat(database.executed()).extracting(ScriptedJdbc.Executed::queryTimeoutSeconds)
                .containsExactly(60, 60);
    }
}
