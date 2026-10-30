package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.ScheduledJobMetrics;
import com.example.backend.scheduling.InMemoryScheduledJobLock;
import com.example.backend.scheduling.RecordingTransactionManager;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.application.ScimMembershipRevocationService;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.JsonNode;

/**
 * The dormancy job's change counters, read from a registry: described for the scrape, and moved
 * by what each run changed — so an unexpected mass lockout or revocation is a spike on a series
 * that already existed. How a declared counter is registered, and why a skipped or failed run
 * counts nothing, are {@code ScheduledJobRegistrationTests}'; its cron and startup record are
 * {@code ScheduledJobScheduleTests}'.
 */
class DormancyScheduleConfigTests {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final InMemoryScheduledJobLock lock = new InMemoryScheduledJobLock();
    private final MutableClock clock = new MutableClock(ScimIdentities.NOW);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private Runnable scheduled;

    @BeforeEach
    void schedule() {
        scheduled = scheduled(new SessionRevocationService(
                new InMemoryAccountSessions(),
                new PendingCommit(),
                audit,
                new RecordingOperationalAlerts()));
    }

    private Runnable scheduled(SessionRevocationService sessions) {
        DormancyService dormancy = new DormancyService(
                users,
                users,
                groups,
                new InMemoryScimWriteLocks(),
                new ScimMembershipRevocationService(users, groups, sessions, audit,
                        TestGroupPermissions.superuserOnly(), () -> {
                        }),
                LoginStateModules.over(users, sessions, new LockoutPolicy(5), audit, clock),
                new ScheduledJobRunService(lock, new RecordingTransactionManager()),
                DormancyPolicy.defaults(),
                clock);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new DormancyScheduleConfig(
                dormancy,
                DormancyPolicy.defaults(),
                new ScheduledJobMetrics(registry, ObservationRegistry.NOOP, clock))
                .configureTasks(registrar);
        List<CronTask> crons = registrar.getCronTaskList();
        assertThat(crons).hasSize(1);
        return crons.getFirst().getRunnable();
    }

    /** The two counters carry their meaning into the scrape, as the alert docs rely on. */
    @Test
    void theCountersAreDescribed() {
        assertThat(registry.find("app.dormancy.users.locked").counter().getId().getDescription())
                .isEqualTo("Users the dormancy job locked");
        assertThat(registry.find("app.dormancy.users.memberships.revoked").counter().getId()
                        .getDescription())
                .isEqualTo("Users whose Group memberships the dormancy job removed");
    }

    /**
     * The counters and the run's {@code job-end} record are written from the run's one report of
     * its counts, so the two can never disagree.
     */
    @Test
    void theCountersMoveByWhatTheJobEndRecordReports() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        users.given(ScimIdentities.user("bob"));
        givenAdmins(ada);
        clock.advanceBy(DormancyPolicy.DEFAULT_MEMBERSHIP_REVOCATION_WINDOW.plusDays(1));

        try (EcsLogCapture logs = EcsLogCapture.attach(new StandardEnvironment())) {
            scheduled.run();

            JsonNode end = logs.records().stream()
                    .filter(record -> "job-end".equals(record.at("/event/type/0").asText()))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertThat(end.at("/dormancy/locked_count").asLong()).isEqualTo(2);
            assertThat(end.at("/dormancy/memberships_revoked_count").asLong()).isEqualTo(1);
            assertThat(locked()).isEqualTo(end.at("/dormancy/locked_count").asDouble());
            assertThat(membershipsRevoked()).isEqualTo(end.at("/dormancy/memberships_revoked_count").asDouble());
        }

        scheduled.run();

        assertThat(locked()).as("a run with nothing more to lock adds nothing").isEqualTo(2);
        assertThat(membershipsRevoked()).isEqualTo(1);
    }

    private void givenAdmins(ScimUser member) {
        groups.createReserved(
                ScimGroup.created(TestGroupPermissions.SUPERUSER_GROUP_ID, "Admins",
                        List.of(ScimGroupMember.reference(member.id())), ScimIdentities.NOW),
                ReservedResourceName.ADMIN_GROUP);
    }

    private double locked() {
        return registry.find("app.dormancy.users.locked").counter().count();
    }

    private double membershipsRevoked() {
        return registry.find("app.dormancy.users.memberships.revoked").counter().count();
    }
}
