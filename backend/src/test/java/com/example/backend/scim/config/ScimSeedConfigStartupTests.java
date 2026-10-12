package com.example.backend.scim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.authorization.domain.InvalidRoleMappingException;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.application.ScimSeedService;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimUser;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The startup step that seeds the directory, then the development fixtures when enabled, then
 * refuses a role mapping naming a Group that does not exist.
 *
 * <p>The runner is taken from a real context, with {@code app.dev-fixtures} bound as a deployment's
 * configuration is, and run as startup runs it; a runner that throws is a failed startup. The
 * directory behind it is in memory, so each test controls exactly which Groups exist.
 * {@code DevelopmentRoleMappingIntegrationTests} proves the same step against Postgres, by starting
 * with the shipped development mapping at all.
 */
class ScimSeedConfigStartupTests {

    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

    private static final ApplicationArguments NO_ARGUMENTS = new DefaultApplicationArguments();

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    /** The Superuser Group, plus a Helpdesk Group that only the fixtures create. */
    private final RoleMapping mapping = RoleMapping.of(
            List.of(new RoleDefinition("Superuser", TestRoleMappings.EVERY_PERMISSION),
                    new RoleDefinition("Helpdesk", List.of("user:read"))),
            List.of(new GroupAssignment(TestRoleMappings.SUPERUSER_GROUP_ID, "Superuser", true),
                    new GroupAssignment(HELPDESK, "Helpdesk", false)));

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(ScimSeedService.class, () -> new ScimSeedService(
                    users, groups, new InMemoryScimWriteLocks(), () -> { },
                    new RecordingAuditTrail(),
                    new MarkingPasswordEncoder(),
                    Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC), mapping,
                    LoginStateModules.over(
                            users, Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC))))
            .withBean(DormancyPolicy.class, DormancyPolicy::defaults)
            .withUserConfiguration(ScimSeedConfig.class)
            .withPropertyValues(
                    "app.auth.bootstrap-username=admin",
                    "app.auth.bootstrap-password=admin-password");

    /**
     * Fixtures off: the Bootstrap Admin is the only User seeded — the baseline member, though
     * configured, is a fixture and is not. (The Helpdesk Group is then missing, so the check after
     * seeding refuses; seeding has already run by then.)
     */
    @Test
    void withTheFixturesOffTheBootstrapAdminIsTheOnlyUserSeeded() {
        contexts.withPropertyValues("app.dev-fixtures.baseline-member=user")
                .run(context -> assertThatThrownBy(
                        () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                        .isInstanceOf(InvalidRoleMappingException.class));

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("admin"))).isPresent();
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("user"))).isEmpty();
    }

    /** Fixtures on with a baseline member: it is seeded in no Group, on the fixture password. */
    @Test
    void theBaselineFixtureIsSeededInNoGroup() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.baseline-member=user",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        ScimUser baseline = users.require("user");
        assertThat(baseline.login().passwordHash()).isEqualTo("encoded:fixture-password");
        assertThat(groups.findAllOrderedByNormalizedDisplayName()).noneMatch(group -> group.hasMember(baseline.id()));
    }

    /** Fixtures off: the Helpdesk Group was never created, so startup refuses, naming it. */
    @Test
    void aMappedGroupThatDoesNotResolveFailsStartup() {
        contexts.run(context -> assertThatThrownBy(
                        () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Group " + HELPDESK + " does not exist"));
        // Seeding ran first: the check refused a directory that had been seeded.
        assertThat(groups.findById(TestRoleMappings.SUPERUSER_GROUP_ID)).isPresent();
    }

    /** Fixtures on: they run before the check, so the Group they create satisfies it. */
    @Test
    void theFixturesRunBeforeTheCheckSoTheirGroupsResolve() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groups.findById(HELPDESK).orElseThrow().hasMember(users.require("helpdesk").id()))
                .isTrue();
    }

    /**
     * A configured dormant fixture is seeded with the fixture password and backdated one day past
     * the lockout window, so the startup dormancy run that follows locks it.
     */
    @Test
    void theDormantFixtureIsSeededADayPastTheLockoutWindow() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.dormant-member=dormant",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        ScimUser dormant = users.require("dormant");
        assertThat(dormant.login().passwordHash()).isEqualTo("encoded:fixture-password");
        assertThat(dormant.dormancyBasis()).isEqualTo(ScimIdentities.NOW
                .minus(DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.plusDays(1)));
    }

    /** No dormant fixture configured: none is seeded. */
    @Test
    void noDormantFixtureIsSeededUnlessOneIsNamed() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("dormant"))).isEmpty();
    }

    /** Enabled with no password is refused rather than seeding Users with no usable credential. */
    @Test
    void enabledFixturesWithoutAPasswordFailStartup() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> assertThatThrownBy(
                                () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("APP_DEV_FIXTURES_PASSWORD"));
        assertThat(groups.findById(HELPDESK)).isEmpty();
    }

    /** Enabled with no fixture Groups listed seeds nothing extra, and the check still runs. */
    @Test
    void enabledFixturesWithNoGroupsStillRunTheCheck() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password")
                .run(context -> assertThatThrownBy(
                                () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                        .isInstanceOf(InvalidRoleMappingException.class)
                        .hasMessageContaining(HELPDESK.toString()));
    }

    /** A deterministic stand-in: hashing is not what these tests are about. */
    private static final class MarkingPasswordEncoder implements PasswordEncoder {

        @Override
        public String encode(CharSequence rawPassword) {
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).equals(encodedPassword);
        }
    }
}
