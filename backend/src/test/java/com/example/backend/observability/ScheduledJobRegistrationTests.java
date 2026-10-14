package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.observability.LogEvent.Operation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;
import tools.jackson.databind.JsonNode;

/**
 * Registering a job through the scheduled-job module ({@link ScheduledJobMetrics#schedule}),
 * shown with a third job that exists only here: naming its job, its cron and its startup
 * fields is all it takes to get the cron task, the trigger in the service time zone, the
 * startup record, the run metrics — and, for the counts it declares, counters written from
 * the same report as its {@code job-end} fields.
 */
class ScheduledJobRegistrationTests {

    /** 08:00 in Singapore, 00:00 UTC: the two zones disagree on the probe job's next run. */
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private static final String JOB = "probe-report";

    /** Daily at 02:15. */
    private static final String CRON = "0 15 2 * * *";

    private static final String DESCRIPTION = "Reports on the probe for the tests";

    /** An operation with no job message of its own, so the generic startup record is used. */
    private static final Operation OPERATION = Operation.DORMANCY_LOCKOUT;

    private static final String COUNTED = "probe.reported_count";

    private static final String UNCOUNTED = "probe.inspected_count";

    private static final String COUNTER = "app.probe.reported";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final ScheduledJobMetrics jobs = new ScheduledJobMetrics(
            registry, ObservationRegistry.NOOP, Clock.fixed(NOW, ZoneOffset.UTC));

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
    void a_third_job_is_registered_by_naming_its_job_its_cron_and_its_startup_fields() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        jobs.schedule(registrar, ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION, () -> ran(Map.of()))
                .startupField("probe.window", "P7D")
                .startupField("probe.limit", 50));

        List<CronTask> crons = registrar.getCronTaskList();
        assertThat(crons).extracting(CronTask::getExpression).containsExactly(CRON);
        assertThat(crons.getFirst().getTrigger().nextExecution(
                        new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))))
                .as("the next 02:15 in Singapore, not the next 02:15 UTC")
                .isEqualTo(Instant.parse("2026-10-01T18:15:00Z"));

        assertThat(records("Scheduled job registered")).singleElement().satisfies(record -> {
            assertThat(record.at("/batch/job/name").asText()).isEqualTo(JOB);
            assertThat(record.at("/trigger/cron/expression").asText()).isEqualTo(CRON);
            assertThat(record.at("/trigger/cron/timezone").asText()).isEqualTo("Asia/Singapore");
            assertThat(record.at("/app/job/description").asText()).isEqualTo(DESCRIPTION);
            assertThat(record.at("/event/category/0").asText()).isEqualTo("configuration");
            assertThat(record.at("/probe/window").asText()).isEqualTo("P7D");
            assertThat(record.at("/probe/limit").asInt()).isEqualTo(50);
        });

        assertThat(registry.find("app.job.runs").tag("job", JOB).counters())
                .as("both outcome series, at zero").hasSize(2)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(registry.get("app.job.last.success").tag("job", JOB).timeGauge()
                .value(TimeUnit.SECONDS)).isEqualTo(NOW.getEpochSecond());

        logs.reset();
        crons.getFirst().getRunnable().run();

        assertThat(registry.get("app.job.runs").tag("job", JOB).tag("outcome", "success")
                .counter().count()).isEqualTo(1);
        List<JsonNode> run = logs.records();
        assertThat(run.getFirst().at("/event/type/0").asText()).isEqualTo("job-start");
        assertThat(run.getLast().at("/event/type/0").asText()).isEqualTo("job-end");
        assertThat(run.getLast().at("/batch/job/name").asText()).isEqualTo(JOB);
    }

    @Test
    void a_job_without_startup_fields_or_counters_logs_only_the_common_startup_fields() {
        jobs.schedule(new ScheduledTaskRegistrar(),
                ScheduledJobSpec.of(JOB, OPERATION, CRON, DESCRIPTION, () -> ran(Map.of())));

        assertThat(records("Scheduled job registered")).singleElement()
                .satisfies(record -> assertThat(record.at("/probe").isMissingNode()).isTrue());
        assertThat(registry.find(COUNTER).counters()).isEmpty();
    }

    @Test
    void a_declared_counter_is_registered_at_zero_and_described_before_any_run() {
        schedule(() -> ran(Map.of(COUNTED, 4L)));

        Counter counter = registry.get(COUNTER).counter();
        assertThat(counter.count()).isZero();
        assertThat(counter.getId().getDescription()).isEqualTo("Reports the probe job made");
    }

    /**
     * The run reports its counts once; each becomes a {@code job-end} field, and a count with a
     * counter declared for it moves that counter by the same amount. A count with no counter is
     * a field only.
     */
    @Test
    void a_runs_counts_become_job_end_fields_and_the_declared_counters() {
        Runnable job = schedule(() -> ran(Map.of(COUNTED, 4L, UNCOUNTED, 7L)));

        job.run();

        JsonNode end = onlyRecord("Scheduled job completed");
        assertThat(end.at("/probe/reported_count").asLong()).isEqualTo(4);
        assertThat(end.at("/probe/inspected_count").asLong()).isEqualTo(7);
        assertThat(registry.get(COUNTER).counter().count()).isEqualTo(4);
        assertThat(registry.find(COUNTER).counters()).as("one counter, the declared one").hasSize(1);

        logs.reset();
        job.run();

        assertThat(registry.get(COUNTER).counter().count()).as("runs accumulate").isEqualTo(8);
    }

    @Test
    void every_declared_counter_moves_by_its_own_count() {
        Runnable job = register(ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION,
                        () -> ran(Map.of(COUNTED, 4L, UNCOUNTED, 7L)))
                .countedAs(COUNTED, COUNTER, "Reports the probe job made")
                .countedAs(UNCOUNTED, "app.probe.inspected", "Items the probe job inspected"));

        job.run();

        assertThat(registry.get(COUNTER).counter().count()).isEqualTo(4);
        assertThat(registry.get("app.probe.inspected").counter().count()).isEqualTo(7);
    }

    /** A skipped run did no work: no counts on its {@code job-end}, and no counter moves. */
    @Test
    void a_skipped_run_reports_and_counts_nothing() {
        Runnable job = schedule(() -> new SkippableJobRun() {
            @Override
            public boolean skipped() {
                return true;
            }

            @Override
            public Map<String, Long> counts() {
                return Map.of(COUNTED, 9L);
            }
        });

        job.run();

        assertThat(onlyRecord("Scheduled job skipped: another run holds its lock")
                .at("/probe").isMissingNode()).isTrue();
        assertThat(registry.get(COUNTER).counter().count()).isZero();
    }

    /** A run that threw rolled back what it did, so its counter does not move. */
    @Test
    void a_failed_run_counts_nothing() {
        Runnable job = schedule(() -> {
            throw new IllegalStateException("the probe failed");
        });

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(COUNTER).counter().count()).isZero();
        assertThat(registry.get("app.job.runs").tag("job", JOB).tag("outcome", "failure")
                .counter().count()).isEqualTo(1);
    }

    /**
     * A failed run's {@code job-end} says what the run had committed when it failed, in the
     * {@code record} array its successful runs carry — for a job that declares it.
     */
    @Test
    void a_failed_run_reports_what_it_had_committed_as_its_record() {
        Runnable job = register(ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION, () -> {
                    throw new IllegalStateException("the probe failed");
                })
                .committedOnFailure(List.of(
                        JobRecord.of("audit_event", "deleted", 0L, "batches", 0L))));

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(onlyRecord("Scheduled job failed").get("record")).isEqualTo(JSON.readTree(
                "[{\"type\":\"audit_event\",\"deleted\":0,\"batches\":0}]"));
    }

    /**
     * A body that commits transactions of its own reports each as it commits ({@link
     * JobProgress#committed}), so a run that fails afterwards — its lock transaction's commit
     * included — counts what had committed, not the schedule's nothing-committed.
     */
    @Test
    void a_failed_run_reports_the_progress_its_body_reported_committed() {
        Runnable job = register(ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION, () -> {
                    JobProgress.committed(List.of(
                            JobRecord.of("audit_event", "deleted", 5L, "batches", 1L)));
                    throw new IllegalStateException("the probe failed after its commit");
                })
                .committedOnFailure(List.of(
                        JobRecord.of("audit_event", "deleted", 0L, "batches", 0L))));

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(onlyRecord("Scheduled job failed").get("record")).isEqualTo(JSON.readTree(
                "[{\"type\":\"audit_event\",\"deleted\":5,\"batches\":1}]"));
    }

    /** What one run reported committed is that run's alone: the next failed run starts over. */
    @Test
    void a_runs_reported_progress_does_not_reach_the_next_run() {
        AtomicInteger run = new AtomicInteger();
        Runnable job = register(ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION, () -> {
                    if (run.incrementAndGet() == 1) {
                        JobProgress.committed(List.of(
                                JobRecord.of("audit_event", "deleted", 5L, "batches", 1L)));
                    }
                    throw new IllegalStateException("the probe failed");
                })
                .committedOnFailure(List.of(
                        JobRecord.of("audit_event", "deleted", 0L, "batches", 0L))));
        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);
        logs.reset();

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(onlyRecord("Scheduled job failed").get("record")).isEqualTo(JSON.readTree(
                "[{\"type\":\"audit_event\",\"deleted\":0,\"batches\":0}]"));
    }

    /** Progress reported outside any scheduled run is nobody's, and is dropped. */
    @Test
    void progress_reported_outside_a_run_reaches_no_run() {
        JobProgress.committed(List.of(JobRecord.of("audit_event", "deleted", 5L, "batches", 1L)));
        Runnable job = register(ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION, () -> {
                    throw new IllegalStateException("the probe failed");
                })
                .committedOnFailure(List.of(
                        JobRecord.of("audit_event", "deleted", 0L, "batches", 0L))));

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(onlyRecord("Scheduled job failed").get("record")).isEqualTo(JSON.readTree(
                "[{\"type\":\"audit_event\",\"deleted\":0,\"batches\":0}]"));
    }

    /** A job that declares nothing about a failed run's records reports none. */
    @Test
    void a_failed_run_of_a_job_declaring_no_records_reports_none() {
        Runnable job = schedule(() -> {
            throw new IllegalStateException("the probe failed");
        });

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(onlyRecord("Scheduled job failed").has("record")).isFalse();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** The probe job with its one declared counter, registered; returns the task it runs. */
    private Runnable schedule(Supplier<? extends SkippableJobRun> task) {
        return register(ScheduledJobSpec
                .of(JOB, OPERATION, CRON, DESCRIPTION, task)
                .countedAs(COUNTED, COUNTER, "Reports the probe job made"));
    }

    /** {@code job} registered through the module; returns the one task it put on the scheduler. */
    private Runnable register(ScheduledJobSpec job) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        jobs.schedule(registrar, job);
        assertThat(registrar.getCronTaskList()).hasSize(1);
        return registrar.getCronTaskList().getFirst().getRunnable();
    }

    private static SkippableJobRun ran(Map<String, Long> counts) {
        return new SkippableJobRun() {
            @Override
            public boolean skipped() {
                return false;
            }

            @Override
            public Map<String, Long> counts() {
                return counts;
            }
        };
    }

    private List<JsonNode> records(String message) {
        return logs.records().stream()
                .filter(record -> message.equals(record.at("/message").asText()))
                .toList();
    }

    private JsonNode onlyRecord(String message) {
        List<JsonNode> matching = records(message);
        assertThat(matching).as(message).hasSize(1);
        return matching.getFirst();
    }
}
