package com.example.backend.auth.config;

import com.example.backend.auth.application.DormancyLogFields;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.observability.ScheduledJobMetrics;
import com.example.backend.observability.ScheduledJobSpec;
import com.example.backend.observability.ServiceTimeZone;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scim.domain.DormancyPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Names the dormancy job for the scheduled-job module ({@link ScheduledJobMetrics#schedule}):
 * its daily cron, what it does, the windows its startup record states, and the two counts its
 * runs report that are also counters.
 *
 * <p>The schedule is fixed rather than configurable: the windows are a deployment decision, the
 * time of day is not, and 04:00 is staggered from the audit retention job's 03:30. Both are evaluated in {@link ServiceTimeZone#ZONE}, the zone the log timestamps
 * are written in.
 *
 * <p>Beside the run metrics every job has, two counters make an unexpected mass lockout or
 * revocation visible as a spike: {@value #LOCKED_USERS} and {@value #ROLES_REVOKED_USERS}. They
 * are moved by the same counts the run's {@code job-end} record carries, once the run has
 * returned — after its transaction committed — so a rolled-back or skipped run counts nothing.
 */
@Configuration
@EnableScheduling
public class DormancyScheduleConfig implements SchedulingConfigurer {

    /** Daily at 04:00. */
    public static final String SCHEDULE = "0 0 4 * * *";

    /** What the dormancy job does, on its startup record. */
    public static final String DESCRIPTION =
            "Locks every User that has not authenticated within the dormancy lockout window, and"
                    + " removes the mapped Group memberships of every User that has not"
                    + " authenticated within the role revocation window";

    /** Users the dormancy job locked, counted per run. */
    static final String LOCKED_USERS = "app.dormancy.users.locked";

    /** Users whose Roles the dormancy job revoked, counted per run. */
    static final String ROLES_REVOKED_USERS = "app.dormancy.users.roles.revoked";

    private final DormancyService dormancy;
    private final DormancyPolicy policy;
    private final ScheduledJobMetrics jobs;

    public DormancyScheduleConfig(
            DormancyService dormancy, DormancyPolicy policy, ScheduledJobMetrics jobs) {
        this.dormancy = dormancy;
        this.policy = policy;
        this.jobs = jobs;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // The job's one identity: its name is the job tag of its run metrics
        // (ScheduledJobMetrics), its batch.job.name and its lock row, and
        // ops/prometheus/alerts.yaml's DormancyJobFailed and DormancyJobNotRunning select on
        // exactly that value.
        jobs.schedule(registrar, ScheduledJobSpec
                .of(ScheduledJob.DORMANCY.jobName(), ScheduledJob.DORMANCY.operation(),
                        SCHEDULE, DESCRIPTION, dormancy::run)
                .startupField(DormancyLogFields.DORMANCY_LOCKOUT_WINDOW,
                        policy.lockoutWindow().toString())
                .startupField(DormancyLogFields.DORMANCY_ROLE_REVOCATION_WINDOW,
                        policy.roleRevocationWindow().toString())
                .countedAs(DormancyLogFields.DORMANCY_LOCKED_COUNT, LOCKED_USERS,
                        "Users the dormancy job locked")
                .countedAs(DormancyLogFields.DORMANCY_ROLES_REVOKED_COUNT, ROLES_REVOKED_USERS,
                        "Users whose mapped Group memberships the dormancy job removed"));
    }
}
