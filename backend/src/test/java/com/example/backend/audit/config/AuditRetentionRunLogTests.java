package com.example.backend.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.application.AuditRetentionService;
import com.example.backend.audit.domain.AuditEventRetention;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.auth.MutableClock;
import com.example.backend.observability.EcsLogCapture;
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
import org.slf4j.MDC;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One retention run, as the scheduler runs it — registered by {@link AuditRetentionScheduleConfig}
 * through the scheduled-job module — logs exactly one summary line: its {@code job-end}, carrying
 * the outcome, {@code batch.job.status}, what it did to the audit events as
 * {@code record=[{type: audit_event, deleted, batches}]}, and how long it took. No per-batch line,
 * and no separate summary beside the end; a run skipped on a held lock is one {@code skipped}
 * line.
 */
class AuditRetentionRunLogTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-09-25T03:30:00Z");

    private final MutableClock clock = new MutableClock(NOW);

    private final InMemoryScheduledJobLock lock = new InMemoryScheduledJobLock();

    private EcsLogCapture logs;

    @BeforeEach
    void attachLogs() {
        MDC.clear();
        logs = EcsLogCapture.attach(new StandardEnvironment());
    }

    @AfterEach
    void detachLogs() {
        logs.close();
        MDC.clear();
    }

    @Test
    void aCompletedRunIsOneSummaryLineNamingWhatItDeletedInHowManyBatches() {
        Runnable run = scheduled(cutoff -> {
            clock.advanceBy(Duration.ofMillis(250));
            return 7;
        });

        run.run();

        assertThat(summary()).isEqualTo(JSON.readTree("""
                {"outcome":"success","status":"completed",
                 "record":[{"type":"audit_event","deleted":7,"batches":1}],
                 "duration_ms":250}"""));
    }

    /** A run that removed nothing still says so, in the same line. */
    @Test
    void aRunThatRemovedNothingStillReportsItsOneBatch() {
        scheduled(cutoff -> 0).run();

        assertThat(summary()).isEqualTo(JSON.readTree("""
                {"outcome":"success","status":"completed",
                 "record":[{"type":"audit_event","deleted":0,"batches":1}],
                 "duration_ms":0}"""));
    }

    /**
     * A run whose delete failed committed nothing — the delete's own transaction rolled back —
     * so its one line is {@code failed} with zero rows in zero batches.
     */
    @Test
    void aFailedRunIsOneFailedLineCountingTheBatchesItCommitted() {
        Runnable run = scheduled(cutoff -> {
            clock.advanceBy(Duration.ofMillis(40));
            throw new DataAccessResourceFailureException("statement timed out");
        });

        assertThatThrownBy(run::run).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(summary()).isEqualTo(JSON.readTree("""
                {"outcome":"failure","status":"failed",
                 "record":[{"type":"audit_event","deleted":0,"batches":0}],
                 "duration_ms":40}"""));
    }

    /**
     * The delete commits on the retention pool, in a transaction of its own, before the run's
     * lock transaction does. A run whose lock transaction then fails to commit has still deleted,
     * so its one {@code failed} line counts the committed batch and its rows.
     */
    @Test
    void aRunThatFailedAfterItsDeleteCommittedCountsTheCommittedBatch() {
        Runnable run = scheduled(cutoff -> 7, new CommitFailingTransactionManager());

        assertThatThrownBy(run::run).isInstanceOf(TransactionSystemException.class);

        assertThat(summary()).isEqualTo(JSON.readTree("""
                {"outcome":"failure","status":"failed",
                 "record":[{"type":"audit_event","deleted":7,"batches":1}],
                 "duration_ms":0}"""));
    }

    /** A run that found another holding the job's lock is one {@code skipped} line. */
    @Test
    void aSkippedRunIsOneSkippedLine() {
        lock.holdElsewhere(ScheduledJob.AUDIT_RETENTION);

        scheduled(cutoff -> 7).run();

        assertThat(summary()).isEqualTo(JSON.readTree("""
                {"outcome":"success","status":"skipped","duration_ms":0}"""));
    }

    /** Beside the run's {@code job-start}, the summary is the one line a run writes. */
    @Test
    void aRunWritesNoLineBesideItsStartAndItsSummary() {
        scheduled(cutoff -> 7).run();

        assertThat(logs.records()).extracting(record -> record.at("/message").asText())
                .containsExactly("Scheduled job started", "Scheduled job completed");
    }

    /** The job's startup record states the retention period its runs delete by. */
    @Test
    void theScheduleStatesTheRetentionPeriod() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        AuditRetentionPolicy policy = new AuditRetentionPolicy(Duration.ofDays(120), null);

        new AuditRetentionScheduleConfig(new AuditRetentionService(cutoff -> 0, policy,
                        new ScheduledJobRunService(lock, new RecordingTransactionManager()), clock),
                policy, new ScheduledJobMetrics(
                        new SimpleMeterRegistry(), ObservationRegistry.NOOP, clock))
                .configureTasks(registrar);

        assertThat(logs.records())
                .filteredOn(record -> "Audit retention job scheduled"
                        .equals(record.at("/message").asText()))
                .singleElement()
                .satisfies(record -> assertThat(record.at("/audit/retention/period").asText())
                        .isEqualTo("PT2880H"));
    }

    // ---- harness -----------------------------------------------------------------------------

    /** The retention job as the application registers it; returns the task the scheduler runs. */
    private Runnable scheduled(AuditEventRetention retention) {
        return scheduled(retention, new RecordingTransactionManager());
    }

    /** As above, with the run's lock transaction on {@code runTransactions}. */
    private Runnable scheduled(AuditEventRetention retention,
            PlatformTransactionManager runTransactions) {
        AuditRetentionPolicy policy = new AuditRetentionPolicy(Duration.ofDays(120), null);
        AuditRetentionService service = new AuditRetentionService(retention, policy,
                new ScheduledJobRunService(lock, runTransactions), clock);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new AuditRetentionScheduleConfig(service, policy, new ScheduledJobMetrics(
                        new SimpleMeterRegistry(), ObservationRegistry.NOOP, clock))
                .configureTasks(registrar);
        logs.reset();
        return registrar.getCronTaskList().getFirst().getRunnable();
    }

    /**
     * A run transaction whose commit the database refuses, as a connection lost before the
     * commit reached it would: it opens and rolls back as Spring's own do, and its commit throws.
     */
    private static final class CommitFailingTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("the run's commit failed");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    /**
     * The run's one {@code job-end}, reduced to the summary fields the issue names, so the
     * assertion is the whole line's claim and nothing else.
     */
    private JsonNode summary() {
        List<JsonNode> ends = logs.records().stream()
                .filter(record -> "job-end".equals(record.at("/event/type/0").asText()))
                .toList();
        assertThat(ends).as("job-end records").hasSize(1);
        JsonNode end = ends.getFirst();
        var summary = JSON.createObjectNode()
                .put("outcome", end.at("/event/outcome").asText())
                .put("status", end.at("/batch/job/status").asText());
        if (end.has("record")) {
            summary.set("record", end.get("record"));
        }
        return summary.set("duration_ms", end.at("/event/duration_ms"));
    }
}
