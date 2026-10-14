package com.example.backend.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.auth.MutableClock;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.JobRecord;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.ScheduledJobMetrics;
import com.example.backend.scheduling.InMemoryScheduledJobLock;
import com.example.backend.scheduling.RecordingTransactionManager;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scheduling.domain.ScheduledJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * What one retention run reports, and what its scheduled run writes. The one summary line, with
 * every field, is {@code AuditRetentionRunLogTests}'.
 *
 * <p>A retention job is the one thing in this service whose correct behaviour and
 * total failure look identical from outside: rows quietly disappearing and rows
 * quietly not disappearing both produce no response and no error. The record each
 * run writes is the only evidence either way, so it is asserted as a field-by-field
 * claim rather than as "something was logged".
 */
class AuditRetentionServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-25T03:30:00Z");

    private final MutableClock clock = new MutableClock(NOW);

    private final InMemoryScheduledJobLock lock = new InMemoryScheduledJobLock();

    private final ScheduledJobRunService runs =
            new ScheduledJobRunService(lock, new RecordingTransactionManager());

    private CapturedLog log;

    @BeforeEach
    void attachLog() {
        log = CapturedLog.attach();
    }

    @AfterEach
    void detachLog() {
        log.close();
    }

    @Test
    void theCutoffIsTheConfiguredWindowBeforeNow() {
        AgedEvents retention = new AgedEvents(3, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(false, 3, 1));
        assertThat(retention.cutoffs).containsExactly(NOW.minus(Duration.ofDays(365)));
        assertThat(lock.attempts()).containsExactly(ScheduledJob.AUDIT_RETENTION);
    }

    /**
     * A run that finds the job's lock held does nothing: it deletes nothing, writes no
     * record, and reports itself skipped so the run's {@code job-end} says {@code lock-held}.
     * The lock it asks for is this job's own.
     */
    @Test
    void aRunThatFindsTheLockHeldDeletesNothingAndReportsItSkipped() {
        AgedEvents retention = new AgedEvents(3, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);
        lock.holdElsewhere(ScheduledJob.AUDIT_RETENTION);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(true, 0, 0));
        assertThat(retention.calls).isZero();
        assertThat(log.withAction(
                Level.INFO, LogEvent.LOCAL_ACTION, Operation.AUDIT_RETENTION.local()))
                .isEmpty();
        assertThat(lock.attempts()).containsExactly(ScheduledJob.AUDIT_RETENTION);
    }

    /**
     * Run as it is scheduled, a run that finds the lock held is {@code job-start} and a
     * {@code job-end} with reason {@code lock-held} — nothing between them.
     */
    @Test
    void aScheduledRunThatFindsTheLockHeldEndsLockHeld() {
        AuditRetentionService service = new AuditRetentionService(
                new AgedEvents(7, Duration.ZERO, clock),
                new AuditRetentionPolicy(Duration.ofDays(120), null), runs, clock);
        lock.holdElsewhere(ScheduledJob.AUDIT_RETENTION);
        Runnable scheduled = new ScheduledJobMetrics(
                        new SimpleMeterRegistry(), ObservationRegistry.NOOP, clock)
                .instrumentLocked(ScheduledJob.AUDIT_RETENTION.jobName(),
                        ScheduledJob.AUDIT_RETENTION.operation(),
                        service::deleteAgedOutEvents);

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            scheduled.run();
            List<JsonNode> run = onThisThread(ecs);

            assertThat(run).extracting(record -> record.at("/message").asText()).containsExactly(
                    "Scheduled job started", "Scheduled job skipped: another run holds its lock");
            assertThat(run.get(1).at("/event/reason").asText()).isEqualTo("lock-held");
        }
    }

    /**
     * What the run did is reported once, in its result — the audit events it deleted, in its
     * one batch — for {@code ScheduledJobMetrics} to write as the run's {@code job-end}
     * {@code record}. The service writes no record of its own.
     */
    @Test
    void theRunReportsItsDeletedEventsInOneBatchAndWritesNoRecordOfItsOwn() {
        AuditRetentionService service = new AuditRetentionService(
                new AgedEvents(7, Duration.ZERO, clock),
                new AuditRetentionPolicy(Duration.ofDays(120), null), runs, clock);

        assertThat(service.deleteAgedOutEvents().records())
                .containsExactly(JobRecord.of("audit_event", "deleted", 7L, "batches", 1L));
        assertThat(log.withAction(
                Level.TRACE, LogEvent.LOCAL_ACTION, Operation.AUDIT_RETENTION.local()))
                .isEmpty();
    }

    /** A failed run rolled back whole, so what it committed is nothing, in no batch. */
    @Test
    void aFailedRunCommittedNoEventInNoBatch() {
        assertThat(AuditRetentionRun.committedByAFailedRun())
                .containsExactly(JobRecord.of("audit_event", "deleted", 0L, "batches", 0L));
    }

    /**
     * Run as it is scheduled — through {@link ScheduledJobMetrics#instrumentLocked} — a run is
     * {@code job-start} and its one summary line, the {@code job-end}, both under one
     * {@code batch.job.run.id}; the next run has another. The end carries the deleted count
     * and the duration.
     */
    @Test
    void aScheduledRunIsJobStartAndOneSummaryJobEndUnderOneRunId() {
        AuditRetentionService service = new AuditRetentionService(
                new AgedEvents(7, Duration.ofMillis(250), clock),
                new AuditRetentionPolicy(Duration.ofDays(120), null), runs, clock);
        Runnable scheduled = new ScheduledJobMetrics(
                        new SimpleMeterRegistry(), ObservationRegistry.NOOP, clock)
                .instrumentLocked(ScheduledJob.AUDIT_RETENTION.jobName(),
                        ScheduledJob.AUDIT_RETENTION.operation(),
                        service::deleteAgedOutEvents);

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            scheduled.run();
            List<JsonNode> first = onThisThread(ecs);
            ecs.reset();
            scheduled.run();
            List<JsonNode> second = onThisThread(ecs);

            assertThat(first).extracting(record -> record.at("/message").asText())
                    .containsExactly("Scheduled job started", "Scheduled job completed");
            assertThat(first).extracting(record -> record.at("/batch/job/run/id").asText())
                    .doesNotContain("").containsOnly(first.getFirst().at("/batch/job/run/id").asText());
            assertThat(first.get(1).at("/record/0/deleted").asLong()).isEqualTo(7);
            assertThat(first.get(1).at("/event/duration_ms").asLong()).isEqualTo(250);
            assertThat(first.get(1).at("/app/event/action").asText()).isEqualTo("audit.retention");
            assertThat(second.getFirst().at("/batch/job/run/id").asText())
                    .isNotBlank()
                    .isNotEqualTo(first.getFirst().at("/batch/job/run/id").asText());
        }
    }

    private static List<JsonNode> onThisThread(EcsLogCapture ecs) {
        String thread = Thread.currentThread().getName();
        return ecs.records().stream()
                .filter(record -> thread.equals(record.at("/process/thread/name").asText()))
                .toList();
    }

    /**
     * A run that removed nothing still reports. "Nothing had aged out" and "the job
     * has not run for a month" are different facts, and only the record tells them
     * apart.
     */
    @Test
    void aRunThatRemovedNothingStillReports() {
        AuditRetentionService service = new AuditRetentionService(
                new AgedEvents(0, Duration.ZERO, clock),
                new AuditRetentionPolicy(null, null),
                runs,
                clock);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(false, 0, 1));
    }


    // ---- batches ------------------------------------------------------------------------------

    /**
     * More aged events than one batch deletes: the run deletes batch after batch until one comes
     * back short of a whole batch, and reports every event in every batch.
     */
    @Test
    void aRunDeletesBatchAfterBatchUntilOneComesBackShort() {
        AgedEvents retention = new AgedEvents(9000, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(false, 9000, 3));
        assertThat(retention.remaining).isZero();
    }

    /**
     * A batch of exactly 4,000 does not say the window is empty, so a run whose aged events fill
     * whole batches takes one more, which deletes nothing, to learn that it is.
     */
    @Test
    void aRunWhoseBatchesComeBackWholeTakesOneMoreToLearnNothingRemains() {
        AgedEvents retention = new AgedEvents(8000, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(false, 8000, 3));
    }

    /** One batch short of a whole one is the run's last, however few it deleted. */
    @Test
    void aBatchOneShortOfWholeIsTheLast() {
        AgedEvents retention = new AgedEvents(3999, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(false, 3999, 1));
    }

    /**
     * Every batch deletes by the one cutoff the run took at its start, however long the batches
     * take: a run does not chase events that age out while it runs.
     */
    @Test
    void everyBatchDeletesByTheCutoffTheRunTookAtItsStart() {
        AgedEvents retention = new AgedEvents(9000, Duration.ofMinutes(1), clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        service.deleteAgedOutEvents();

        Instant cutoff = NOW.minus(Duration.ofDays(365));
        assertThat(retention.cutoffs).containsExactly(cutoff, cutoff, cutoff);
    }

    /**
     * The batches are the run's body, inside the one run under the job's lock: the lock is asked
     * for once, however many batches follow.
     */
    @Test
    void aRunOfManyBatchesTakesTheJobsLockOnce() {
        AuditRetentionService service = new AuditRetentionService(
                new AgedEvents(9000, Duration.ZERO, clock),
                new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        service.deleteAgedOutEvents();

        assertThat(lock.attempts()).containsExactly(ScheduledJob.AUDIT_RETENTION);
    }

    /**
     * A retention port over a number of aged events: each batch deletes as many as a batch may,
     * and costs a fixed amount of time, by moving the clock the service reads. That is what lets
     * the logged duration be asserted as a value rather than as "greater than zero", which would
     * pass for a job that did nothing at all.
     */
    private static final class AgedEvents implements AuditEventRetention {

        private final Duration cost;
        private final MutableClock clock;

        private long remaining;

        private final List<Instant> cutoffs = new java.util.ArrayList<>();

        private int calls;

        AgedEvents(long aged, Duration cost, MutableClock clock) {
            this.remaining = aged;
            this.cost = cost;
            this.clock = clock;
        }

        @Override
        public long deleteBatchOccurredBefore(Instant cutoff) {
            cutoffs.add(cutoff);
            calls++;
            clock.advanceBy(cost);
            long deleted = Math.min(remaining, 4000);
            remaining -= deleted;
            return deleted;
        }
    }
}
