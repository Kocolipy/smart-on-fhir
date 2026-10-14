package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;

/**
 * The one wrapper every scheduled job runs through, read back from what it publishes: the
 * run metrics from a registry — what each outcome does to the counters and the last-success
 * time, and what they read before the job has run at all, which is what the never-ran alert
 * depends on — and the run's log records from the bytes the production ECS encoder writes.
 */
class ScheduledJobMetricsTests {

    private static final Instant SCHEDULED = Instant.parse("2026-09-30T00:00:00Z");

    /** A job with both an action and a local name, so the classification is visible in full. */
    private static final Operation OPERATION = Operation.DORMANCY;

    /** Records a job's body emits on its own, as a service's run summary does. */
    private static final Logger body = LoggerFactory.getLogger("job-body");

    /** The run's one record of its own, when a test's job has nothing else to do. */
    private static final String INSIDE = "inside the job";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final AtomicReference<Instant> now = new AtomicReference<>(SCHEDULED);

    /** Every observation a run opened, in the order they stopped. */
    private final List<Observation.Context> stopped = new ArrayList<>();

    private final ObservationRegistry observations = ObservationRegistry.create();

    {
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
    }

    private final ScheduledJobMetrics jobs = new ScheduledJobMetrics(registry, observations, new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    private EcsLogCapture logs;

    /** A run that took its lock and did the work: what every job body below reports. */
    private static final SkippableJobRun RAN = () -> false;

    /**
     * Wraps {@code task} as a job that always takes its lock, so these tests can state a body
     * as a plain statement. The skipped path has tests of its own, which pass a
     * {@link SkippableJobRun} directly.
     */
    private Runnable instrument(String job, Operation operation, Runnable task) {
        return jobs.instrumentLocked(job, operation, () -> {
            task.run();
            return RAN;
        });
    }

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

    // ---- metrics ----------------------------------------------------------------------------

    @Test
    void before_any_run_both_outcomes_read_zero_and_last_success_reads_the_scheduling_time() {
        instrument("probe", OPERATION, () -> { });

        assertThat(runs("success")).isZero();
        assertThat(runs("failure")).isZero();
        assertThat(lastSuccessSeconds()).isEqualTo(SCHEDULED.getEpochSecond());
    }

    @Test
    void a_successful_run_counts_a_success_and_moves_the_last_success_time() {
        Runnable job = instrument("probe", OPERATION, () -> { });
        now.set(SCHEDULED.plusSeconds(3600));

        job.run();

        assertThat(runs("success")).isEqualTo(1);
        assertThat(runs("failure")).isZero();
        assertThat(lastSuccessSeconds()).isEqualTo(SCHEDULED.plusSeconds(3600).getEpochSecond());
    }

    /** The last-success time is when the run ENDED, not when it began. */
    @Test
    void the_last_success_time_is_the_end_of_the_run() {
        Runnable job = instrument("probe", OPERATION, () -> advance(Duration.ofSeconds(90)));

        job.run();

        assertThat(lastSuccessSeconds()).isEqualTo(SCHEDULED.plusSeconds(90).getEpochSecond());
    }

    @Test
    void a_failed_run_counts_a_failure_rethrows_it_and_leaves_the_last_success_time() {
        IllegalStateException failure = new IllegalStateException("job failed");
        Runnable job = instrument("probe", OPERATION, () -> {
            throw failure;
        });
        now.set(SCHEDULED.plusSeconds(3600));

        assertThatThrownBy(job::run).isSameAs(failure);

        assertThat(runs("failure")).isEqualTo(1);
        assertThat(runs("success")).isZero();
        assertThat(lastSuccessSeconds()).isEqualTo(SCHEDULED.getEpochSecond());
    }

    /** The success counter moves only after the job returned, never before it ran. */
    @Test
    void the_job_itself_runs_once_per_run() {
        int[] ran = {0};
        Runnable job = instrument("probe", OPERATION, () -> ran[0]++);

        job.run();
        job.run();

        assertThat(ran[0]).isEqualTo(2);
        assertThat(runs("success")).isEqualTo(2);
    }

    /** A skip is not a failure: another run is doing the work. */
    @Test
    void a_skipped_run_counts_as_a_success() {
        jobs.instrumentLocked("probe", OPERATION, () -> () -> true).run();

        assertThat(runs("success")).isEqualTo(1);
        assertThat(runs("failure")).isZero();
    }

    @Test
    void jobs_are_told_apart_by_their_job_tag() {
        instrument("probe", OPERATION, () -> { }).run();
        instrument("other-job", OPERATION, () -> { });

        assertThat(registry.get("app.job.runs").tag("job", "other-job")
                .tag("outcome", "success").counter().count()).isZero();
        assertThat(runs("success")).isEqualTo(1);
    }

    /**
     * Both series carry a description, which is the {@code # HELP} line an operator reads in
     * the scrape beside the alert that fired on it.
     */
    @Test
    void both_series_are_described_for_the_scrape() {
        instrument("probe", OPERATION, () -> { });

        assertThat(registry.get("app.job.runs").tag("job", "probe").tag("outcome", "success")
                .counter().getId().getDescription()).isNotBlank();
        assertThat(registry.get("app.job.runs").tag("job", "probe").tag("outcome", "failure")
                .counter().getId().getDescription()).isNotBlank();
        assertThat(registry.get("app.job.last.success").tag("job", "probe")
                .timeGauge().getId().getDescription()).isNotBlank();
    }

    // ---- the observation --------------------------------------------------------------------

    /**
     * A run executes inside an observation of its own — the one Boot's tracing handler
     * turns into the run's trace — named for runs and tagged with the job, and the
     * observation ends when the run does.
     */
    @Test
    void each_run_executes_inside_its_own_observation_tagged_with_the_job() {
        List<Observation> current = new ArrayList<>();
        Runnable job = instrument("probe", OPERATION,
                () -> current.add(observations.getCurrentObservation()));

        job.run();
        job.run();

        assertThat(current).hasSize(2).doesNotContainNull();
        assertThat(current.get(0)).isNotSameAs(current.get(1));
        assertThat(stopped).hasSize(2);
        assertThat(stopped).allSatisfy(context -> {
            assertThat(context.getName()).isEqualTo("app.job.run");
            assertThat(context.getLowCardinalityKeyValue("job").getValue()).isEqualTo("probe");
            assertThat(context.getError()).isNull();
        });
        assertThat(current).extracting(Observation::getContextView)
                .containsExactlyElementsOf(stopped);
        assertThat(observations.getCurrentObservation()).as("closed after the run").isNull();
    }

    /** No observation is opened by scheduling a job, only by running it. */
    @Test
    void scheduling_a_job_opens_no_observation() {
        instrument("probe", OPERATION, () -> { });

        assertThat(stopped).isEmpty();
    }

    /** A failed run's observation ends too, carrying the failure, and the failure still escapes. */
    @Test
    void a_failed_run_ends_its_observation_with_the_failure() {
        IllegalStateException failure = new IllegalStateException("job failed");
        Runnable job = instrument("probe", OPERATION, () -> {
            throw failure;
        });

        assertThatThrownBy(job::run).isSameAs(failure);

        assertThat(stopped).singleElement()
                .satisfies(context -> assertThat(context.getError()).isSameAs(failure));
    }

    // ---- the run's records ------------------------------------------------------------------

    /**
     * One run is {@code job-start}, then whatever the job logs, then {@code job-end}, all
     * under one fresh {@code batch.job.run.id} and the job's name and trigger. Both ends are
     * classified as the job's operation, and the end says how long the run took.
     */
    @Test
    void a_run_is_job_start_then_the_jobs_own_records_then_job_end_under_one_run_id() {
        Runnable job = instrument("probe", OPERATION, () -> {
            body.info(INSIDE);
            advance(Duration.ofMillis(250));
        });

        job.run();

        List<JsonNode> records = onThisThread();
        assertThat(records).extracting(record -> record.at("/message").asText())
                .containsExactly("Scheduled job started", INSIDE, "Scheduled job completed");
        String runId = records.getFirst().at("/batch/job/run/id").asText();
        assertThat(UUID.fromString(runId)).as("a UUID").isNotNull();
        assertThat(records).allSatisfy(record -> {
            assertThat(record.at("/batch/job/run/id").asText()).isEqualTo(runId);
            assertThat(record.at("/batch/job/name").asText()).isEqualTo("probe");
            assertThat(record.at("/trigger/type").asText()).isEqualTo("scheduled");
        });

        JsonNode start = records.get(0);
        assertThat(start.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(start.at("/event/kind").asText()).isEqualTo("event");
        assertThat(texts(start.at("/event/category"))).containsExactly("batch");
        assertThat(texts(start.at("/event/type"))).containsExactly("job-start");
        assertThat(start.at("/event/action").asText()).isEqualTo("user-administration");
        assertThat(start.at("/app/event/action").asText()).isEqualTo("identity.dormancy");
        assertThat(start.at("/event/outcome").isMissingNode()).isTrue();

        JsonNode end = records.get(2);
        assertThat(end.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(texts(end.at("/event/category"))).containsExactly("batch");
        assertThat(texts(end.at("/event/type"))).containsExactly("job-end");
        assertThat(end.at("/event/action").asText()).isEqualTo("user-administration");
        assertThat(end.at("/app/event/action").asText()).isEqualTo("identity.dormancy");
        assertThat(end.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(end.at("/event/duration_ms").asLong()).isEqualTo(250);
        assertThat(end.at("/event/reason").isMissingNode()).as("not a skip").isTrue();
        assertThat(end.has("error_code")).isFalse();
    }

    /**
     * What a run counted rides on its {@code job-end} record, so the record that says the run
     * ended also says what it did; a run that counted nothing adds nothing, and a skipped run's
     * counts are never reported — it did no work.
     */
    @Test
    void a_runs_counts_ride_on_its_job_end_and_a_skip_reports_none() {
        SkippableJobRun counted = new SkippableJobRun() {
            @Override
            public boolean skipped() {
                return false;
            }

            @Override
            public Map<String, Long> counts() {
                return Map.of("probe.locked_count", 3L);
            }
        };
        SkippableJobRun skippedWithCounts = new SkippableJobRun() {
            @Override
            public boolean skipped() {
                return true;
            }

            @Override
            public Map<String, Long> counts() {
                return Map.of("probe.locked_count", 9L);
            }
        };

        jobs.instrumentLocked("probe", OPERATION, () -> counted).run();
        JsonNode end = onlyRecord("Scheduled job completed");
        assertThat(end.at("/probe/locked_count").asInt()).isEqualTo(3);
        assertThat(end.at("/event/outcome").asText()).isEqualTo("success");

        logs.reset();
        jobs.instrumentLocked("probe", OPERATION, () -> RAN).run();
        assertThat(onlyRecord("Scheduled job completed").at("/probe").isMissingNode()).isTrue();

        logs.reset();
        jobs.instrumentLocked("probe", OPERATION, () -> skippedWithCounts).run();
        JsonNode skip = onlyRecord("Scheduled job skipped: another run holds its lock");
        assertThat(skip.at("/probe").isMissingNode()).isTrue();
        assertThat(skip.at("/event/reason").asText()).isEqualTo("lock-held");
    }

    /**
     * What a run did to each kind of record rides on its {@code job-end} as one {@code record}
     * array, one object per kind, so the record that says the run ended also says what it did.
     */
    @Test
    void a_runs_records_ride_on_its_job_end_as_one_record_array() {
        jobs.instrumentLocked("probe", OPERATION, () -> recording(false)).run();

        assertThat(onlyRecord("Scheduled job completed").get("record")).isEqualTo(JSON.readTree(
                "[{\"type\":\"audit_event\",\"deleted\":3,\"batches\":1}]"));
    }

    /** A skipped run did nothing, so its one line carries no record of any kind. */
    @Test
    void a_skipped_run_carries_no_record() {
        jobs.instrumentLocked("probe", OPERATION, () -> recording(true)).run();

        assertThat(onlyRecord("Scheduled job skipped: another run holds its lock").has("record"))
                .isFalse();
    }

    /** A run that reports records of no kind adds no {@code record} field. */
    @Test
    void a_run_with_no_records_adds_no_record_field() {
        jobs.instrumentLocked("probe", OPERATION, () -> RAN).run();

        assertThat(onlyRecord("Scheduled job completed").has("record")).isFalse();
    }

    @Test
    void every_run_gets_a_run_id_of_its_own() {
        Runnable job = instrument("probe", OPERATION, () -> body.info(INSIDE));

        job.run();
        String first = onlyRecord(INSIDE).at("/batch/job/run/id").asText();
        logs.reset();
        job.run();
        String second = onlyRecord(INSIDE).at("/batch/job/run/id").asText();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    /**
     * A failed run ends in exactly one {@code job-end}, at {@code ERROR}: the failure outcome,
     * how long the run took, the error fields, and the exception attached — which is where
     * {@code error.type} comes from. The failure counter still moves, and the failure still
     * escapes to the scheduler.
     */
    @Test
    void a_failed_run_ends_in_one_error_job_end_with_the_error_fields_and_the_exception() {
        Runnable job = instrument("probe", OPERATION, () -> {
            advance(Duration.ofMillis(40));
            throw new IllegalStateException("job failed");
        });

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        List<JsonNode> records = onThisThread();
        assertThat(records).extracting(record -> record.at("/message").asText())
                .containsExactly("Scheduled job started", "Scheduled job failed");
        JsonNode end = records.get(1);
        assertThat(end.at("/log/level").asText()).isEqualTo("ERROR");
        assertThat(texts(end.at("/event/type"))).containsExactly("job-end");
        assertThat(texts(end.at("/event/category"))).containsExactly("batch");
        assertThat(end.at("/event/action").asText()).isEqualTo("user-administration");
        assertThat(end.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(end.at("/event/severity").asText()).isEqualTo("high");
        assertThat(end.at("/event/duration_ms").asLong()).isEqualTo(40);
        assertThat(end.at("/error_code").asInt()).isEqualTo(500);
        assertThat(end.at("/error_category").asText()).isEqualTo("application");
        assertThat(end.at("/error_follow_up_action").asBoolean()).isTrue();
        assertThat(end.at("/error/type").asText()).isEqualTo(IllegalStateException.class.getName());
        assertThat(end.at("/error/stack_trace").asText()).isNotBlank();
        assertThat(end.at("/batch/job/run/id").asText())
                .isEqualTo(records.get(0).at("/batch/job/run/id").asText());
        assertThat(runs("failure")).isEqualTo(1);
    }

    /** A database failure is categorised as one, so it routes to whoever owns the database. */
    @Test
    void a_database_failure_is_categorised_as_database() {
        Runnable job = instrument("probe", OPERATION, () -> {
            throw new DataIntegrityViolationException("refused");
        });

        assertThatThrownBy(job::run).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(onlyRecord("Scheduled job failed").at("/error_category").asText())
                .isEqualTo("database");
    }

    @Test
    void failures_are_categorised_by_where_they_came_from() {
        assertThat(ScheduledJobMetrics.category(new DataIntegrityViolationException("refused")))
                .isEqualTo(ErrorCategory.DATABASE);
        assertThat(ScheduledJobMetrics.category(new CannotCreateTransactionException("down")))
                .isEqualTo(ErrorCategory.DATABASE);
        assertThat(ScheduledJobMetrics.category(new IllegalStateException("bug")))
                .isEqualTo(ErrorCategory.APPLICATION);
    }

    /**
     * A run the database refused for this service's own rights is {@code cert/auth}, still under
     * {@code error.code} {@code 500}: the code is an HTTP status, never the database's number.
     */
    @Test
    void a_run_refused_its_rights_by_the_database_is_cert_auth_under_500() {
        Runnable job = instrument("probe", OPERATION, () -> {
            throw new DataIntegrityViolationException("refused",
                    new java.sql.SQLException("permission denied", "42501", 229));
        });

        assertThatThrownBy(job::run).isInstanceOf(DataIntegrityViolationException.class);

        JsonNode end = onlyRecord("Scheduled job failed");
        assertThat(end.at("/error_category").asText()).isEqualTo("cert/auth");
        assertThat(end.at("/error_code").asInt()).isEqualTo(500);
    }

    /**
     * A run that found its lock held ends in one INFO {@code job-end} that says so, and none
     * that claims the work was done.
     */
    @Test
    void a_run_that_found_its_lock_held_ends_in_one_lock_held_job_end() {
        Runnable job = jobs.instrumentLocked("probe", OPERATION, () -> {
            advance(Duration.ofMillis(5));
            return () -> true;
        });

        job.run();

        List<JsonNode> records = onThisThread();
        assertThat(records).extracting(record -> record.at("/message").asText())
                .containsExactly("Scheduled job started", "Scheduled job skipped: another run holds its lock");
        JsonNode end = records.get(1);
        assertThat(end.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(texts(end.at("/event/type"))).containsExactly("job-end");
        assertThat(end.at("/event/action").asText()).isEqualTo("user-administration");
        assertThat(end.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(end.at("/event/reason").asText()).isEqualTo("lock-held");
        assertThat(end.at("/event/duration_ms").asLong()).isEqualTo(5);
        assertThat(end.at("/batch/job/run/id").asText())
                .isEqualTo(records.get(0).at("/batch/job/run/id").asText());
    }

    /** A locked job that did get its lock ends like any other run. */
    @Test
    void a_locked_job_that_ran_ends_as_completed() {
        jobs.instrumentLocked("probe", OPERATION, () -> () -> false).run();

        JsonNode end = onlyRecord("Scheduled job completed");
        assertThat(end.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(end.at("/event/reason").isMissingNode()).isTrue();
    }

    /** Nothing of a run is left in the scheduler thread's context, however the run ended. */
    @Test
    void no_job_key_outlives_a_run_however_it_ends() {
        instrument("probe", OPERATION, () -> { }).run();
        assertThat(context()).isEmpty();

        jobs.instrumentLocked("probe", OPERATION, () -> () -> true).run();
        assertThat(context()).isEmpty();

        Runnable failing = instrument("probe", OPERATION, () -> {
            throw new IllegalStateException("job failed");
        });
        assertThatThrownBy(failing::run).isInstanceOf(IllegalStateException.class);
        assertThat(context()).isEmpty();
    }

    /** The job keys are in the context while the job body runs, not only on the wrapper's records. */
    @Test
    void the_job_body_runs_with_the_run_identity_in_context() {
        List<Map<String, String>> seen = new ArrayList<>();
        instrument("probe", OPERATION, () -> seen.add(context())).run();

        assertThat(seen).singleElement().satisfies(inside -> assertThat(inside)
                .containsEntry(LogContext.JOB_NAME, "probe")
                .containsEntry(LogContext.TRIGGER_TYPE, "scheduled")
                .containsKey(LogContext.JOB_RUN_ID));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** A run that removed three audit events in one batch, or skipped. */
    private static SkippableJobRun recording(boolean skipped) {
        return new SkippableJobRun() {
            @Override
            public boolean skipped() {
                return skipped;
            }

            @Override
            public List<JobRecord> records() {
                return List.of(JobRecord.of("audit_event", "deleted", 3L, "batches", 1L));
            }
        };
    }

    private void advance(Duration by) {
        now.set(now.get().plus(by));
    }

    private static Map<String, String> context() {
        return Optional.ofNullable(MDC.getCopyOfContextMap()).orElseGet(Map::of);
    }

    private List<JsonNode> onThisThread() {
        String thread = Thread.currentThread().getName();
        return logs.records().stream()
                .filter(record -> thread.equals(record.at("/process/thread/name").asText()))
                .toList();
    }

    private JsonNode onlyRecord(String message) {
        List<JsonNode> matching = onThisThread().stream()
                .filter(record -> message.equals(record.at("/message").asText()))
                .toList();
        assertThat(matching).as(message).hasSize(1);
        return matching.getFirst();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private double runs(String outcome) {
        return registry.get("app.job.runs").tag("job", "probe").tag("outcome", outcome)
                .counter().count();
    }

    private double lastSuccessSeconds() {
        return registry.get("app.job.last.success").tag("job", "probe")
                .timeGauge().value(TimeUnit.SECONDS);
    }
}
