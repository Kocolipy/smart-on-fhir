package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.ScheduledJobMetrics;
import com.example.backend.scheduling.InMemoryScheduledJobLock;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.DormancyPolicy;
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
 * The dormancy job's change counters, read from a registry: registered at zero when the job is
 * scheduled, and moved by what each run changed — so an unexpected mass lockout or revocation is
 * a spike on a series that already existed. Its cron and startup record are
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
                groups,
                sessions,
                lock,
                DormancyPolicy.defaults(),
                TestRoleMappings.superuserOnly(),
                audit,
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

    @Test
    void theCountersExistAtZeroBeforeAnyRun() {
        assertThat(locked()).isZero();
        assertThat(rolesRevoked()).isZero();
        assertThat(registry.find("app.job.runs").tag("job", "dormancy").counters()).hasSize(2);
    }

    /** The two counters carry their meaning into the scrape, as the alert docs rely on. */
    @Test
    void theCountersAreDescribed() {
        assertThat(registry.find("app.dormancy.users.locked").counter().getId().getDescription())
                .isEqualTo("Users the dormancy job locked");
        assertThat(registry.find("app.dormancy.users.roles.revoked").counter().getId()
                        .getDescription())
                .isEqualTo("Users whose mapped Group memberships the dormancy job removed");
    }

    @Test
    void eachRunAddsTheUsersItLockedAndTheUsersWhoseRolesItRevoked() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        users.given(ScimIdentities.user("bob"));
        groups.createReserved(
                ScimGroup.created(TestRoleMappings.SUPERUSER_GROUP_ID, "Admins",
                        List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW),
                ReservedResourceName.ADMIN_GROUP);
        clock.advanceBy(DormancyPolicy.DEFAULT_ROLE_REVOCATION_WINDOW.plusDays(1));

        scheduled.run();

        assertThat(locked()).isEqualTo(2);
        assertThat(rolesRevoked()).isEqualTo(1);

        scheduled.run();

        assertThat(locked()).as("nothing more to lock").isEqualTo(2);
        assertThat(rolesRevoked()).isEqualTo(1);
    }

    /** A skipped run changed nothing and counts nothing. */
    @Test
    void aSkippedRunCountsNothing() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        givenAdmins(ada);
        clock.advanceBy(DormancyPolicy.DEFAULT_ROLE_REVOCATION_WINDOW.plusDays(1));
        lock.holdElsewhere(ScheduledJob.DORMANCY);

        scheduled.run();

        assertThat(locked()).isZero();
        assertThat(rolesRevoked()).isZero();
    }

    /** A failed run — its transaction rolled back after a User was locked — counts nothing. */
    @Test
    void aFailedRunCountsNothing() {
        users.given(ScimIdentities.user("ada"));
        clock.advanceBy(DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.plusDays(1));
        Runnable failing = scheduled(new SessionRevocationService(
                new InMemoryAccountSessions(),
                work -> {
                    throw new IllegalStateException("revocation could not be scheduled");
                },
                audit,
                new RecordingOperationalAlerts()));

        assertThatThrownBy(failing::run).isInstanceOf(IllegalStateException.class);

        assertThat(users.require("ada").login().isLocked())
                .as("the run got as far as locking").isTrue();
        assertThat(locked()).isZero();
        assertThat(rolesRevoked()).isZero();
        assertThat(registry.find("app.job.runs").tag("job", "dormancy").tag("outcome", "failure")
                .counter().count()).isEqualTo(1);
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
        clock.advanceBy(DormancyPolicy.DEFAULT_ROLE_REVOCATION_WINDOW.plusDays(1));

        try (EcsLogCapture logs = EcsLogCapture.attach(new StandardEnvironment())) {
            scheduled.run();

            JsonNode end = logs.records().stream()
                    .filter(record -> "job-end".equals(record.at("/event/type/0").asText()))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertThat(end.at("/dormancy/locked_count").asLong()).isEqualTo(2);
            assertThat(end.at("/dormancy/roles_revoked_count").asLong()).isEqualTo(1);
            assertThat(locked()).isEqualTo(end.at("/dormancy/locked_count").asDouble());
            assertThat(rolesRevoked()).isEqualTo(end.at("/dormancy/roles_revoked_count").asDouble());
        }
    }

    private void givenAdmins(ScimUser member) {
        groups.createReserved(
                ScimGroup.created(TestRoleMappings.SUPERUSER_GROUP_ID, "Admins",
                        List.of(ScimGroupMember.reference(member.id())), ScimIdentities.NOW),
                ReservedResourceName.ADMIN_GROUP);
    }

    private double locked() {
        return registry.find("app.dormancy.users.locked").counter().count();
    }

    private double rolesRevoked() {
        return registry.find("app.dormancy.users.roles.revoked").counter().count();
    }
}
