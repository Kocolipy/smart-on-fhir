package com.example.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.observability.EcsLogCapture;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import tools.jackson.databind.JsonNode;

/**
 * The audit retention job against real SQL Server: a run over more aged events than one batch
 * deletes, overlapped by a second run and by an audit append, and a lone run.
 *
 * <p>Both runs are the task the scheduler actually holds, not a re-wrapped copy of the
 * service, so the claim covers the deployed wiring — the lock, the {@code lock-held} end the
 * wrapper writes for a skipped run, the retention pool and the migration that gives the job its
 * lock row.
 *
 * <p>Every pause is made by the database rather than by a test hook in the job: the test holds
 * an update lock on one aged event, as the owner login (the app login may not lock an audit
 * row), and the run's batch waits on it exactly as it would on a slow transaction of another
 * session. Each wait is observed in {@code sys.dm_exec_requests}, read by the server's
 * administrator, never assumed after a sleep.
 *
 * <p>Every event this class inserts has aged out, so the runs under test remove them and
 * nothing is left to clean up but the one fresh event the append test writes, which the trail
 * keeps. That the job keeps what has not aged out is {@code AuditAppendOnlyIntegrationTests}'.
 *
 * <p>The annotations match {@code EcsLogFormatTests}', so the two share one cached context.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class AuditRetentionSerializationIntegrationTests {

    /** One batch's worth and then some: two batches, the second short. */
    private static final int AGED = 4500;

    /** Past the 5,000 locks at which SQL Server escalates one statement's row locks. */
    private static final int PAST_ESCALATION = 6000;

    private static final int BATCH = 4000;

    private static final String INSERT_EVENT = """
            INSERT INTO audit_events
                (id, occurred_at, operation, outcome, resource_type, status_class)
                VALUES (?, ?, 'LOGOUT', 'SUCCESS', 'User', 'ok')""";

    private static final String COUNT_EVENT = "SELECT count(*) FROM audit_events WHERE id = ?";

    private static final String COUNT_AGED_OUT =
            "SELECT count(*) FROM audit_events WHERE occurred_at < ?";

    /** The aged event at a position in the table's clustered order, oldest first. */
    private static final String AGED_EVENT_AT = """
            SELECT id FROM audit_events WHERE occurred_at < ?
             ORDER BY occurred_at, id OFFSET ? ROWS FETCH NEXT 1 ROWS ONLY""";

    private static final String NEWEST_AGED_EVENT = """
            SELECT TOP (1) id FROM audit_events WHERE occurred_at < ?
             ORDER BY occurred_at DESC, id DESC""";

    private static final String HOLD_EVENT =
            "SELECT id FROM audit_events WITH (UPDLOCK, ROWLOCK) WHERE id = ?";

    /** A request waiting on a lock the given session holds. */
    private static final String BLOCKED_BY = """
            SELECT count(*) FROM sys.dm_exec_requests
             WHERE blocking_session_id = ? AND wait_type LIKE 'LCK[_]M[_]%'""";

    /** How often the server has escalated a lock on any of the audit table's indexes. */
    private static final String LOCK_ESCALATIONS = """
            SELECT COALESCE(SUM(index_lock_promotion_count), 0)
              FROM sys.dm_db_index_operational_stats(
                       DB_ID(), OBJECT_ID('audit_events'), NULL, NULL)""";

    /** A lock on the whole audit table that would keep an append out: an escalated one. */
    private static final String TABLE_LOCKS = """
            SELECT count(*) FROM sys.dm_tran_locks
             WHERE resource_database_id = DB_ID() AND resource_type = 'OBJECT'
               AND resource_associated_entity_id = OBJECT_ID('audit_events')
               AND request_mode IN ('S', 'U', 'X', 'SIX', 'UIX')""";

    private static final String FIRST_RUN_THREAD = "retention-first-run";

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private AuditRetentionPolicy policy;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    @Autowired
    private Environment environment;

    private EcsLogCapture logs;

    private final ExecutorService threads = Executors.newFixedThreadPool(3);

    @BeforeEach
    void attachLogs() {
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void detachLogs() {
        logs.close();
        threads.shutdownNow();
    }

    /**
     * A run over more than one batch of aged events, observed between its batches and inside one:
     *
     * <ul>
     *   <li>Paused inside its first batch, on the 4,000th aged event, it has locked nearly a whole
     *       batch of rows — and an audit append still goes straight in, because the batch holds
     *       row locks only: no lock on the table, and no escalation counted.
     *   <li>Paused inside its second batch, after the first committed — the first 4,000 are gone
     *       for every other session — a second run started there finds the job's lock still held
     *       and skips: the batch's commit, on the retention pool, released nothing (#66).
     *   <li>Let go, it deletes every aged event, in two batches, and writes one summary line.
     * </ul>
     */
    @Test
    void aRunOverTwoBatchesNeverEscalatesAndASecondRunBetweenThemSkips() throws Exception {
        Instant cutoff = Instant.now().minus(policy.period());
        insertAgedEvents(cutoff.minus(Duration.ofHours(1)), AGED);
        long aged = agedOut(cutoff);
        assertThat(aged).as("aged events now: more than one batch, fewer than two")
                .isGreaterThan(BATCH).isLessThanOrEqualTo(2L * BATCH);
        JdbcTemplate owner = logins.owner().jdbc();
        UUID lastOfTheFirstBatch = owner.queryForObject(
                AGED_EVENT_AT, UUID.class, cutoff.atOffset(ZoneOffset.UTC), BATCH - 1);
        UUID lastOfTheSecondBatch = owner.queryForObject(
                NEWEST_AGED_EVENT, UUID.class, cutoff.atOffset(ZoneOffset.UTC));
        RowHold inTheFirstBatch = hold(lastOfTheFirstBatch);
        RowHold inTheSecondBatch = hold(lastOfTheSecondBatch);
        long escalationsBefore = escalations();
        Runnable retention = theScheduledRetentionTask();
        try {
            Future<?> first = threads.submit(() -> {
                Thread.currentThread().setName(FIRST_RUN_THREAD);
                retention.run();
            });

            // Inside the first batch: nearly a whole batch of rows locked, nothing committed.
            awaitBlockedBy(inTheFirstBatch, () -> agedOut(cutoff) == aged);
            UUID appended = appendWithinTenSeconds();
            assertThat(List.of(count(COUNT_EVENT, appended), tableLocks()))
                    .as("the append went in, and nothing holds the table")
                    .containsExactly(1L, 0L);

            // Between the batches: the first committed, the second waiting.
            inTheFirstBatch.release();
            awaitBlockedBy(inTheSecondBatch, () -> agedOut(cutoff) == aged - BATCH);
            String secondThread = Thread.currentThread().getName();
            retention.run();
            assertThat(agedOut(cutoff)).as("the second run deleted nothing")
                    .isEqualTo(aged - BATCH);

            inTheSecondBatch.release();
            first.get(60, TimeUnit.SECONDS);

            assertThat(escalations()).as("lock escalations on the audit table")
                    .isEqualTo(escalationsBefore);
            assertThat(agedOut(cutoff)).isZero();

            List<JsonNode> firstRun = onThread(FIRST_RUN_THREAD);
            assertThat(firstRun).extracting(record -> record.at("/message").asText())
                    .as("one summary line, and no line per batch")
                    .containsExactly("Scheduled job started", "Scheduled job completed");
            JsonNode summary = firstRun.get(1);
            assertThat(List.of(summary.at("/record/0/deleted").asLong(),
                            summary.at("/record/0/batches").asLong()))
                    .containsExactly(aged, 2L);
            assertThat(summary.at("/batch/job/status").asText()).isEqualTo("completed");
            assertThat(summary.at("/event/outcome").asText()).isEqualTo("success");

            List<JsonNode> secondRun = onThread(secondThread);
            assertThat(secondRun).extracting(record -> record.at("/message").asText())
                    .containsExactly("Scheduled job started",
                            "Scheduled job skipped: another run holds its lock");
            assertThat(secondRun.get(1).at("/event/reason").asText()).isEqualTo("lock-held");
            assertThat(secondRun.get(1).at("/batch/job/status").asText()).isEqualTo("skipped");
            assertThat(secondRun.get(1).at("/batch/job/name").asText())
                    .isEqualTo("audit-retention");
        } finally {
            inTheFirstBatch.release();
            inTheSecondBatch.release();
        }
    }

    /**
     * More aged events than SQL Server lets one statement lock row by row: deleted in one
     * statement they would be escalated to a lock on the whole table, which every audit append
     * would queue behind. In batches of 4,000 the server never escalates, and every event still
     * goes.
     */
    @Test
    void aRunOverMoreEventsThanTheEscalationThresholdNeverEscalates() {
        Instant cutoff = Instant.now().minus(policy.period());
        insertAgedEvents(cutoff.minus(Duration.ofHours(1)), PAST_ESCALATION);
        long aged = agedOut(cutoff);
        long escalationsBefore = escalations();

        theScheduledRetentionTask().run();

        assertThat(List.of(escalations() - escalationsBefore, agedOut(cutoff)))
                .as("lock escalations on the audit table, and the aged events left")
                .containsExactly(0L, 0L);
        assertThat(aged).as("aged events the run had to delete").isGreaterThan(5000);
    }

    /**
     * The single-instance case: with no other run, a run takes the lock, deletes what has aged
     * out in one short batch and logs it, and the next run takes the lock again.
     */
    @Test
    void aLoneRunTakesTheLockDeletesAndLogsAndTheNextRunCanTakeItAgain() {
        UUID agedOut = insertEventAt(
                Instant.now().minus(policy.period()).minus(Duration.ofDays(30)));
        Runnable retention = theScheduledRetentionTask();
        String thread = Thread.currentThread().getName();

        retention.run();
        List<JsonNode> firstRun = onThread(thread);
        logs.reset();
        retention.run();
        List<JsonNode> secondRun = onThread(thread);

        assertThat(count(COUNT_EVENT, agedOut)).isZero();
        assertThat(firstRun).extracting(record -> record.at("/message").asText())
                .containsExactly("Scheduled job started", "Scheduled job completed");
        assertThat(firstRun.get(1).at("/record/0/deleted").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(firstRun.get(1).at("/record/0/batches").asLong()).isEqualTo(1);
        assertThat(secondRun).extracting(record -> record.at("/message").asText())
                .as("the first run's commit released the lock")
                .containsExactly("Scheduled job started", "Scheduled job completed");
    }

    // ---- harness -----------------------------------------------------------------------------

    private Runnable theScheduledRetentionTask() {
        List<Runnable> tasks = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(task -> task.getExpression().equals(policy.schedule()))
                .map(Task::getRunnable)
                .toList();
        assertThat(tasks).as("the scheduled retention task").hasSize(1);
        return tasks.getFirst();
    }

    /** One aged event's row, update-locked by the owner login until released. */
    private final class RowHold {

        private final CountDownLatch release = new CountDownLatch(1);

        private final int session;

        private final Future<?> holding;

        private RowHold(UUID id) throws Exception {
            CountDownLatch held = new CountDownLatch(1);
            int[] spid = new int[1];
            DatabaseLogins.Login owner = logins.owner();
            holding = threads.submit(() -> owner.transactions().executeWithoutResult(status -> {
                JdbcTemplate asOwner = owner.jdbc();
                asOwner.queryForList(HOLD_EVENT, UUID.class, id);
                spid[0] = asOwner.queryForObject("SELECT @@SPID", Integer.class);
                held.countDown();
                awaitQuietly(release);
            }));
            assertThat(held.await(30, TimeUnit.SECONDS)).as("the row is held").isTrue();
            session = spid[0];
        }

        void release() throws Exception {
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
        }
    }

    private RowHold hold(UUID id) throws Exception {
        return new RowHold(id);
    }

    /**
     * Waits until a request is waiting on {@code hold}'s lock and {@code alsoTrue} holds — the
     * batch is where the test means it to be.
     */
    private void awaitBlockedBy(RowHold hold, BooleanSupplier alsoTrue) throws InterruptedException {
        JdbcTemplate observer = logins.superuser().jdbc();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            Integer waiting = observer.queryForObject(BLOCKED_BY, Integer.class, hold.session);
            if (waiting != null && waiting > 0 && alsoTrue.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the retention batch never waited on session " + hold.session);
    }

    /**
     * An audit append, as the app login appends one, given ten seconds: an append queued behind
     * an escalated table lock would time out instead.
     */
    private UUID appendWithinTenSeconds() {
        JdbcTemplate app = logins.app().jdbc();
        app.setQueryTimeout(10);
        UUID id = UUID.randomUUID();
        app.update(INSERT_EVENT, id, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private long escalations() {
        return logins.superuser().jdbc().queryForObject(LOCK_ESCALATIONS, Long.class);
    }

    private long tableLocks() {
        return logins.superuser().jdbc().queryForObject(TABLE_LOCKS, Long.class);
    }

    private long agedOut(Instant cutoff) {
        return count(COUNT_AGED_OUT, cutoff.atOffset(ZoneOffset.UTC));
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }

    /**
     * The job's records on one thread. Hikari's own lines are left out: the retention pool starts
     * on its first use, which may be the run on that thread, and its start is not the job's.
     */
    private List<JsonNode> onThread(String thread) {
        return logs.records().stream()
                .filter(record -> thread.equals(record.at("/process/thread/name").asText()))
                .filter(record -> !record.at("/log/logger").asText().startsWith("com.zaxxer.hikari"))
                .toList();
    }

    /** {@code count} events, a microsecond apart, the newest at {@code newest}. */
    private void insertAgedEvents(Instant newest, int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int event = count - 1; event >= 0; event--) {
            rows.add(new Object[] {UUID.randomUUID(),
                    newest.minusNanos(event * 1_000L).atOffset(ZoneOffset.UTC)});
        }
        jdbc.batchUpdate(INSERT_EVENT, rows);
    }

    private UUID insertEventAt(Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_EVENT, id, occurredAt.atOffset(ZoneOffset.UTC));
        return id;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
