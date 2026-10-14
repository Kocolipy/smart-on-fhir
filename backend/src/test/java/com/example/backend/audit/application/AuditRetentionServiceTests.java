package com.example.backend.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.auth.MutableClock;
import com.example.backend.observability.EcsLogCapture;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * What one retention run reports.
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
        CountingRetention retention = new CountingRetention(3, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(false, 3));
        assertThat(retention.cutoff).isEqualTo(NOW.minus(Duration.ofDays(365)));
        assertThat(lock.attempts()).containsExactly(ScheduledJob.AUDIT_RETENTION);
    }

    /**
     * A run that finds the job's lock held does nothing: it deletes nothing, writes no
     * summary, and reports itself skipped so the run's {@code job-end} says {@code lock-held}.
     * The lock it asks for is this job's own.
     */
    @Test
    void aRunThatFindsTheLockHeldDeletesNothingAndReportsItSkipped() {
        CountingRetention retention = new CountingRetention(3, Duration.ZERO, clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(365), null), runs, clock);
        lock.holdElsewhere(ScheduledJob.AUDIT_RETENTION);

        assertThat(service.deleteAgedOutEvents()).isEqualTo(new AuditRetentionRun(true, 0));
        assertThat(retention.calls).isZero();
        assertThat(log.withAction(
                Level.INFO, LogEvent.LOCAL_ACTION, Operation.AUDIT_RETENTION.local()))
                .isEmpty();
        assertThat(lock.attempts()).containsExactly(ScheduledJob.AUDIT_RETENTION);
    }

    /**
     * Run as it is scheduled, a run that finds the lock held is {@code job-start} and a
     * {@code job-end} with reason {@code lock-held} — no summary between them.
     */
    @Test
    void aScheduledRunThatFindsTheLockHeldEndsLockHeld() {
        AuditRetentionService service = new AuditRetentionService(
                new CountingRetention(7, Duration.ZERO, clock),
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
     * The run's own record keeps what only the job knows — the rows it removed and the period
     * it removed them by — and leaves the outcome and the duration to the run's
     * {@code job-end}, which {@code ScheduledJobMetrics} writes around it. It is the run's
     * summary, not its end, so it is classified {@code info}.
     */
    @Test
    void eachRunLogsItsDeletedRowCountAndPeriod() {
        CountingRetention retention =
                new CountingRetention(7, Duration.ofMillis(250), clock);
        AuditRetentionService service = new AuditRetentionService(
                retention, new AuditRetentionPolicy(Duration.ofDays(120), null), runs, clock);

        service.deleteAgedOutEvents();

        Map<String, Object> fields = CapturedLog.fields(onlyRunRecord());
        assertThat(fields).containsEntry(AuditLogFields.RETENTION_DELETED_ROWS, 7L);
        assertThat(fields).containsEntry(AuditLogFields.RETENTION_PERIOD, "PT2880H");
        assertThat(fields).containsEntry(LogEvent.CATEGORY, List.of("batch"));
        assertThat(fields).containsEntry(LogEvent.TYPE, List.of("info"));
        assertThat(fields).doesNotContainKeys(LogEvent.OUTCOME, LogEvent.DURATION_MS);
    }

    /**
     * Run as it is scheduled — through {@link ScheduledJobMetrics#instrumentLocked} — a run is
     * {@code job-start}, this job's summary and {@code job-end}, all three under one
     * {@code batch.job.run.id}; the next run has another. The end carries the duration the
     * summary no longer does.
     */
    @Test
    void aScheduledRunIsJobStartTheSummaryAndJobEndUnderOneRunId() {
        AuditRetentionService service = new AuditRetentionService(
                new CountingRetention(7, Duration.ofMillis(250), clock),
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

            assertThat(first).extracting(record -> record.at("/message").asText()).containsExactly(
                    "Scheduled job started", "Audit retention run complete", "Scheduled job completed");
            assertThat(first).extracting(record -> record.at("/batch/job/run/id").asText())
                    .doesNotContain("").containsOnly(first.getFirst().at("/batch/job/run/id").asText());
            assertThat(first.get(1).at("/audit/retention/deleted_rows").asLong()).isEqualTo(7);
            assertThat(first.get(2).at("/event/duration_ms").asLong()).isEqualTo(250);
            assertThat(first.get(2).at("/app/event/action").asText()).isEqualTo("audit.retention");
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
                new CountingRetention(0, Duration.ZERO, clock),
                new AuditRetentionPolicy(null, null),
                runs,
                clock);

        service.deleteAgedOutEvents();

        assertThat(CapturedLog.fields(onlyRunRecord()))
                .containsEntry(AuditLogFields.RETENTION_DELETED_ROWS, 0L);
    }

    private ILoggingEvent onlyRunRecord() {
        List<ILoggingEvent> records = log.withAction(
                Level.INFO, LogEvent.LOCAL_ACTION, Operation.AUDIT_RETENTION.local());
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    /**
     * A retention port that reports a fixed row count and costs a fixed amount of
     * time, by moving the clock the service reads. That is what lets the logged
     * duration be asserted as a value rather than as "greater than zero", which
     * would pass for a job that did nothing at all.
     */
    private static final class CountingRetention implements AuditEventRetention {

        private final long deleted;
        private final Duration cost;
        private final MutableClock clock;

        private Instant cutoff;

        private int calls;

        CountingRetention(long deleted, Duration cost, MutableClock clock) {
            this.deleted = deleted;
            this.cost = cost;
            this.clock = clock;
        }

        @Override
        public long deleteOccurredBefore(Instant cutoff) {
            this.cutoff = cutoff;
            calls++;
            clock.advanceBy(cost);
            return deleted;
        }
    }
}
