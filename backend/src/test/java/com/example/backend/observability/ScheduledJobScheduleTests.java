package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.application.AuditRetentionService;
import com.example.backend.audit.config.AuditRetentionScheduleConfig;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.auth.config.DormancyScheduleConfig;
import com.example.backend.scim.domain.DormancyPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.SimpleTriggerContext;
import tools.jackson.databind.JsonNode;

/**
 * The scheduled jobs as the scheduler holds them: each cron is evaluated in Singapore time,
 * the zone the log timestamps are written in, and each job runs through
 * {@link ScheduledJobMetrics#instrumentLocked} — which is what gives a run its own trace.
 */
@SpringBootTest
@Import(com.example.backend.ContainerTestConfiguration.class)
class ScheduledJobScheduleTests {

    /** 08:00 in Singapore, 00:00 UTC: a moment at which the two zones disagree on every job's next run. */
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Environment environment;

    @Autowired
    private DormancyService dormancyJob;

    @Autowired
    private AuditRetentionService retentionJob;

    @Autowired
    private ScheduledJobMetrics jobs;

    @Autowired
    private DormancyPolicy dormancy;

    @Autowired
    private AuditRetentionPolicy retention;

    @Test
    void everyCronIsEvaluatedInSingaporeTime() {
        List<CronTask> crons = cronTasks();
        assertThat(crons).as("the two scheduled jobs: audit retention and dormancy").hasSize(2);

        for (CronTask cron : crons) {
            CronExpression expression = CronExpression.parse(cron.getExpression());
            Instant inSingapore = expression.next(NOW.atZone(SINGAPORE)).toInstant();
            Instant inUtc = expression.next(NOW.atZone(ZoneOffset.UTC)).toInstant();
            assertThat(inSingapore).as("the zones disagree, so the check discriminates").isNotEqualTo(inUtc);

            Instant next = cron.getTrigger().nextExecution(
                    new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC)));

            assertThat(next).as(cron.getExpression()).isEqualTo(inSingapore);
        }
    }

    /**
     * Each job is registered with the run metrics — and so with the observation around each
     * run — under its own name. Series exist from scheduling, so their presence proves the
     * job went through {@link ScheduledJobMetrics#instrumentLocked}.
     */
    @Test
    void everyJobRunsThroughTheInstrumentedWrapper() {
        assertThat(List.of("audit-retention", "dormancy"))
                .allSatisfy(job -> assertThat(registry.find("app.job.runs").tag("job", job).counters())
                        .as(job).hasSize(2));
        assertThat(registry.find("app.job.runs").tag("job", "inactivity").counters())
                .as("the removed jobs are no longer scheduled").isEmpty();
        assertThat(registry.find("app.job.runs").tag("job", "dormant-authority-revocation")
                .counters()).isEmpty();
    }

    /**
     * The dormancy job's change counters exist from scheduling, at zero, so an unexpected mass
     * lockout or revocation is a spike on a series that was already there.
     */
    @Test
    void theDormancyChangeCountersAreRegisteredFromScheduling() {
        assertThat(registry.find("app.dormancy.users.locked").counter()).isNotNull();
        assertThat(registry.find("app.dormancy.users.roles.revoked").counter()).isNotNull();
    }

    private List<CronTask> cronTasks() {
        return scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
    }

    /**
     * The configuration built and driven here, against a registrar of the test's own, so what
     * {@code configureTasks} registers and logs is observed while the test runs rather than
     * inferred from a context started earlier. The one dormancy job is registered once, on its
     * cron, in Singapore time; the startup record states its name, schedule, zone and both
     * windows; and running the registered task gives the run a trace, which only the instrumented
     * wrapper opens — this thread is in no request.
     */
    @Test
    void theDormancyConfigurationRegistersOneJobInstrumentedInSingaporeTime() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        try (EcsLogCapture logs = EcsLogCapture.attach(environment)) {
            new DormancyScheduleConfig(dormancyJob, dormancy, jobs)
                    .configureTasks(registrar);

            List<CronTask> crons = registrar.getCronTaskList();
            assertThat(crons).extracting(CronTask::getExpression)
                    .containsExactly(DormancyScheduleConfig.SCHEDULE);
            assertThat(DormancyScheduleConfig.SCHEDULE).as("daily at 04:00").isEqualTo("0 0 4 * * *");
            crons.forEach(ScheduledJobScheduleTests::assertEvaluatedInSingaporeTime);
            assertThat(crons.getFirst().getTrigger().nextExecution(
                            new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))))
                    .as("the next 04:00 in Singapore after 08:00 Singapore time")
                    .isEqualTo(Instant.parse("2026-10-01T20:00:00Z"));

            List<JsonNode> scheduled = records(logs, "Dormancy job scheduled");
            assertThat(scheduled).singleElement().satisfies(record -> {
                assertThat(record.at("/batch/job/name").asText()).isEqualTo("dormancy");
                assertThat(record.at("/trigger/cron/expression").asText())
                        .isEqualTo(DormancyScheduleConfig.SCHEDULE);
                assertThat(record.at("/trigger/cron/timezone").asText()).isEqualTo("Asia/Singapore");
                assertThat(record.at("/app/job/description").asText())
                        .isEqualTo(DormancyScheduleConfig.DESCRIPTION).isNotBlank();
                assertThat(record.at("/app/event/action").asText()).isEqualTo("identity.dormancy");
                assertThat(record.at("/dormancy/lockout/window").asText())
                        .isEqualTo(dormancy.lockoutWindow().toString());
                assertThat(record.at("/dormancy/role_revocation/window").asText())
                        .isEqualTo(dormancy.membershipRevocationWindow().toString());
            });

            for (CronTask cron : crons) {
                logs.reset();
                cron.getRunnable().run();
                List<JsonNode> run = onThisThread(logs);
                assertThat(run).as(cron.getExpression()).isNotEmpty()
                        .allSatisfy(record -> assertThat(record.at("/trace/id").asText()).isNotBlank());
                assertJobStartThenJobEndUnderOneRunId(run);
                assertNoJobKeyLeftInContext();
            }
        }
    }

    @Test
    void theRetentionConfigurationRegistersItsJobInstrumentedInSingaporeTime() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        try (EcsLogCapture logs = EcsLogCapture.attach(environment)) {
            new AuditRetentionScheduleConfig(retentionJob, retention, jobs).configureTasks(registrar);

            List<CronTask> crons = registrar.getCronTaskList();
            assertThat(crons).extracting(CronTask::getExpression).containsExactly(retention.schedule());
            assertEvaluatedInSingaporeTime(crons.getFirst());

            assertThat(records(logs, "Audit retention job scheduled")).singleElement().satisfies(record -> {
                assertThat(record.at("/batch/job/name").asText()).isEqualTo("audit-retention");
                assertThat(record.at("/trigger/cron/expression").asText()).isEqualTo(retention.schedule());
                assertThat(record.at("/trigger/cron/timezone").asText()).isEqualTo("Asia/Singapore");
                assertThat(record.at("/app/job/description").asText())
                        .isEqualTo(AuditRetentionScheduleConfig.RETENTION_DESCRIPTION).isNotBlank();
                assertThat(record.at("/app/event/action").asText()).isEqualTo("audit.retention");
                assertThat(record.at("/audit/retention/period").asText())
                        .isEqualTo(retention.period().toString());
            });

            logs.reset();
            crons.getFirst().getRunnable().run();
            List<JsonNode> run = onThisThread(logs);
            assertThat(run).isNotEmpty()
                    .allSatisfy(record -> assertThat(record.at("/trace/id").asText()).isNotBlank());
            assertJobStartThenJobEndUnderOneRunId(run);
            assertNoJobKeyLeftInContext();
        }
    }

    /**
     * The run's first record is its {@code job-start} and its last its {@code job-end}, and
     * every record between them — the job's own — carries the same run id.
     */
    private static void assertJobStartThenJobEndUnderOneRunId(List<JsonNode> run) {
        assertThat(run.getFirst().at("/event/type/0").asText()).isEqualTo("job-start");
        assertThat(run.getLast().at("/event/type/0").asText()).isEqualTo("job-end");
        String runId = run.getFirst().at("/batch/job/run/id").asText();
        assertThat(runId).isNotBlank();
        assertThat(run).allSatisfy(record ->
                assertThat(record.at("/batch/job/run/id").asText()).isEqualTo(runId));
    }

    private static void assertNoJobKeyLeftInContext() {
        assertThat(Optional.ofNullable(MDC.getCopyOfContextMap()).orElseGet(Map::of).keySet())
                .noneMatch(key -> key.startsWith("batch.") || key.startsWith("trigger."));
    }

    private static void assertEvaluatedInSingaporeTime(CronTask cron) {
        Instant expected = CronExpression.parse(cron.getExpression()).next(NOW.atZone(SINGAPORE)).toInstant();
        assertThat(cron.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))))
                .as(cron.getExpression())
                .isEqualTo(expected);
    }

    private static List<JsonNode> records(EcsLogCapture logs, String message) {
        return logs.records().stream()
                .filter(record -> message.equals(record.at("/message").asText()))
                .toList();
    }

    private static List<JsonNode> onThisThread(EcsLogCapture logs) {
        String thread = Thread.currentThread().getName();
        return logs.records().stream()
                .filter(record -> thread.equals(record.at("/process/thread/name").asText()))
                .toList();
    }
}
