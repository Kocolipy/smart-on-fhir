package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.scheduling.InMemoryScheduledJobLock;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.ScimUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * The development profile's one startup run of the dormancy job: present only with the
 * development fixtures on, and — when it runs — the real job, logged with its counts.
 */
class DevDormancyStartupConfigTests {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final InMemoryScheduledJobLock lock = new InMemoryScheduledJobLock();
    private final MutableClock clock = new MutableClock(ScimIdentities.NOW);

    private final DormancyService dormancy = new DormancyService(
            users,
            groups,
            new SessionRevocationService(
                    new InMemoryAccountSessions(),
                    new PendingCommit(),
                    audit,
                    new RecordingOperationalAlerts()),
            lock,
            DormancyPolicy.defaults(),
            TestRoleMappings.superuserOnly(),
            audit,
            clock);

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withBean(DormancyService.class, () -> dormancy)
            .withUserConfiguration(DevDormancyStartupConfig.class);

    @Test
    void itIsAbsentUnlessTheDevelopmentFixturesAreOn() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(DevDormancyStartupConfig.class));
        contexts.withPropertyValues("app.dev-fixtures.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DevDormancyStartupConfig.class));
        contexts.withPropertyValues("app.dev-fixtures.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevDormancyStartupConfig.class));
    }

    /** One run of the real job: the dormant User locked for DORMANCY, audited, and counted. */
    @Test
    void aRunLocksTheDormantFixtureThroughTheRealJobAndLogsItsCounts() {
        ScimUser dormant = users.given(ScimIdentities.user("dormant"));
        clock.advanceBy(DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.plusDays(1));

        try (EcsLogCapture logs = EcsLogCapture.attach(new StandardEnvironment())) {
            new DevDormancyStartupConfig(dormancy).runOnce();

            JsonNode record = logs.records().stream()
                    .filter(entry -> entry.at("/message").asText()
                            .equals("Dormancy job run at startup for the development fixtures"))
                    .findFirst().orElseThrow();
            assertThat(record.at("/app/event/action").asText()).isEqualTo("identity.dormancy");
            assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
            assertThat(record.at("/dormancy/locked_count").asInt()).isEqualTo(1);
            assertThat(record.at("/dormancy/roles_revoked_count").asInt()).isZero();
        }
        assertThat(users.require("dormant").login().lockCause()).isEqualTo(LockCause.DORMANCY);
        assertThat(audit.of(AuditOperation.DORMANCY_LOCKOUT)).containsExactly(
                new Recorded(AuditOperation.DORMANCY_LOCKOUT, null, dormant.id(), null));
        assertThat(lock.attempts()).isEqualTo(List.of(ScheduledJob.DORMANCY));
    }

    /** Another instance's run holding the lock: this one does nothing and says so. */
    @Test
    void aRunThatFindsTheLockHeldSaysSo() {
        lock.holdElsewhere(ScheduledJob.DORMANCY);

        try (EcsLogCapture logs = EcsLogCapture.attach(new StandardEnvironment())) {
            new DevDormancyStartupConfig(dormancy).runOnce();

            JsonNode record = logs.records().stream()
                    .filter(entry -> entry.at("/message").asText()
                            .equals("Dormancy job run at startup for the development fixtures"))
                    .findFirst().orElseThrow();
            assertThat(record.at("/event/reason").asText()).isEqualTo("lock-held");
            assertThat(record.at("/dormancy").isMissingNode()).isTrue();
        }
    }
}
