package com.example.backend.scim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.InvalidGroupPermissionsException;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.application.ScimSeedService;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimUser;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The startup step that seeds the directory, then the development fixtures when enabled — their
 * Group Permissions after their Groups — then refuses a directory without its Admin group or with
 * a stored Group Permission no Group may carry.
 *
 * <p>The runner is taken from a real context, with {@code app.dev-fixtures} bound as a deployment's
 * configuration is, and run as startup runs it; a runner that throws is a failed startup. The
 * directory behind it is in memory, so each test controls exactly which Groups exist.
 * {@code DevelopmentFixturesIntegrationTests} proves the same step against SQL Server.
 */
class ScimSeedConfigStartupTests {

    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

    private static final ApplicationArguments NO_ARGUMENTS = new DefaultApplicationArguments();

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryGroupPermissions groupPermissions = InMemoryGroupPermissions.over(groups);

    /** The seeding step over the in-memory directory, with no configuration at all. */
    private final ApplicationContextRunner directory = new ApplicationContextRunner()
            .withBean(ScimSeedService.class, () -> new ScimSeedService(
                    users, groups, new InMemoryScimWriteLocks(), () -> { },
                    new RecordingAuditTrail(),
                    new MarkingPasswordEncoder(),
                    Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC), groupPermissions,
                    LoginStateModules.over(
                            users, Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC))))
            .withBean(DormancyPolicy.class, DormancyPolicy::defaults)
            .withUserConfiguration(ScimSeedConfig.class);

    /** The same, with a Bootstrap Admin configured. */
    private final ApplicationContextRunner contexts = directory.withPropertyValues(
            "app.auth.bootstrap-username=admin",
            "app.auth.bootstrap-password=admin-password");

    /** The same, under the {@code local} profile a developer's run has: the fixtures' one home. */
    private final ApplicationContextRunner local =
            contexts.withPropertyValues("spring.profiles.active=local");

    /**
     * Fixtures off: the Bootstrap Admin is the only User seeded — the baseline member, though
     * configured, is a fixture and is not — and startup succeeds with the Admin group alone.
     */
    @Test
    void withTheFixturesOffTheBootstrapAdminIsTheOnlyUserSeeded() {
        contexts.withPropertyValues("app.dev-fixtures.baseline-member=guest")
                .run(context -> assertThatCode(
                        () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                        .doesNotThrowAnyException());

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("admin"))).isPresent();
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("guest"))).isEmpty();
        assertThat(groups.findById(TestGroupPermissions.SUPERUSER_GROUP_ID)).isPresent();
    }

    /** Fixtures on with a baseline member: it is seeded in no Group, on the fixture password. */
    @Test
    void theBaselineFixtureIsSeededInNoGroup() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.baseline-member=guest",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        ScimUser baseline = users.require("guest");
        assertThat(baseline.login().passwordHash()).isEqualTo("encoded:fixture-password");
        assertThat(groups.findAllOrderedByNormalizedDisplayName()).noneMatch(group -> group.hasMember(baseline.id()));
    }

    /**
     * Fixtures on with no Groups configured: the baseline member is seeded, and no Group beside the
     * Admin group every directory gets.
     */
    @Test
    void fixturesWithNoGroupsConfiguredSeedTheBaselineMemberAlone() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.baseline-member=guest")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.require("guest").login().passwordHash()).isEqualTo("encoded:fixture-password");
        assertThat(groups.findAllOrderedByNormalizedDisplayName())
                .extracting(ScimGroup::id)
                .containsExactly(TestGroupPermissions.SUPERUSER_GROUP_ID);
    }

    /** A Medplum Practitioner id, the userName Epic Login links a clinician by. */
    private static final String CLINICIAN = "af74a102-ebaf-46ef-b445-4f72157c473f";

    /** Fixtures on with an Epic clinician named: one User, in no Group, on the fixture password. */
    @Test
    void theEpicClinicianFixtureIsSeededInNoGroup() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.epic-clinician=" + CLINICIAN,
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        ScimUser clinician = users.require(CLINICIAN);
        assertThat(clinician.login().passwordHash()).isEqualTo("encoded:fixture-password");
        assertThat(groups.findAllOrderedByNormalizedDisplayName())
                .noneMatch(group -> group.hasMember(clinician.id()));
    }

    /** Unset, or blank as an unset environment variable binds, the fixtures seed no clinician. */
    @Test
    void withNoEpicClinicianNamedTheFixturesSeedNoSuchUser() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.epic-clinician=")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.size()).isEqualTo(1);
    }

    /** The fixtures off, the clinician is not seeded either: it is a fixture like the rest. */
    @Test
    void withTheFixturesOffTheEpicClinicianIsNotSeeded() {
        contexts.withPropertyValues("app.dev-fixtures.epic-clinician=" + CLINICIAN)
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of(CLINICIAN))).isEmpty();
    }

    /** The existing refusal outside `local` covers the clinician: nothing is seeded. */
    @Test
    void theEpicClinicianFixtureIsRefusedOutsideTheLocalProfile() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.epic-clinician=" + CLINICIAN)
                .run(refusesStartup("'local' profile is not active"));

        assertThat(users.size()).isZero();
    }

    /** Fixtures off: the Bootstrap Admin owes its first-login change, as in a deployment. */
    @Test
    void withTheFixturesOffTheBootstrapAdminOwesAPasswordChange() {
        contexts.run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.require("admin").login().isPasswordChangeRequired()).isTrue();
    }

    /** Fixtures on: a developer's Bootstrap Admin signs straight in. */
    @Test
    void withTheFixturesOnTheBootstrapAdminOwesNoPasswordChange() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.require("admin").login().isPasswordChangeRequired()).isFalse();
    }

    /** Fixtures on: the Group they create exists, with its User in it. */
    @Test
    void theFixturesCreateTheirGroupsWithTheirUsers() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groups.findById(HELPDESK).orElseThrow().hasMember(users.require("helpdesk").id()))
                .isTrue();
    }

    /** The fixture Group Permissions apply after the fixture Groups, so the Group they name exists. */
    @Test
    void theFixtureGroupPermissionsApplyToTheGroupTheFixturesCreate() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk",
                        "app.dev-fixtures.group-permissions[0].group=" + HELPDESK,
                        "app.dev-fixtures.group-permissions[0].permissions=user:read,account:unlock")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groupPermissions.carriedBy(List.of(HELPDESK))).isEqualTo(Map.of(
                HELPDESK, Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK)));
    }

    /** With the fixtures off, their Group Permissions are not applied either. */
    @Test
    void withTheFixturesOffNoGroupPermissionIsSeeded() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.group-permissions[0].group=" + HELPDESK,
                        "app.dev-fixtures.group-permissions[0].permissions=user:read")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groupPermissions.findAll()).isEmpty();
    }

    /**
     * A stored Group Permission naming one no Group may carry — a Permission removed from code
     * whose rows no migration deleted — fails startup with a message naming it, after seeding.
     */
    @Test
    void aStoredGroupPermissionNoGroupMayCarryFailsStartup() {
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk", List.of(), ScimIdentities.NOW));
        groupPermissions.store(HELPDESK, "connector:write");

        contexts.run(context -> assertThatThrownBy(
                        () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                .isInstanceOf(InvalidGroupPermissionsException.class)
                .hasMessage("Invalid Group Permissions: Group " + HELPDESK
                        + " carries unknown Permission 'connector:write'."
                        + " Delete the rows, or restore the Permission in code"));
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("admin"))).isPresent();
    }

    // ---- the shipped development fixtures --------------------------------------------------

    /** The shipped document, enabled as a local run enables it, under these profiles. */
    private ApplicationContextRunner shipped(String profiles) {
        return contexts
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.location=classpath:authorization.yaml",
                        "spring.profiles.active=" + profiles,
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password");
    }

    /**
     * The {@code local} profile seeds each development Group's Permissions — the access levels the
     * e2e suite signs in at — and the Admin group none.
     */
    @Test
    void theLocalProfileSeedsTheDevelopmentGroupsPermissions() {
        shipped("local").run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        Set<Permission> auditorReads = Set.of(Permission.USER_READ, Permission.GROUP_READ,
                Permission.OPS_READ, Permission.CONNECTOR_READ, Permission.AUDIT_READ);
        Set<Permission> helpdesk = new HashSet<>(auditorReads);
        helpdesk.add(Permission.ACCOUNT_UNLOCK);
        assertThat(groupPermissions.carriedBy(List.of(
                        group("a001"), group("a003"), group("a006"))))
                .isEqualTo(Map.of(group("a003"), auditorReads, group("a006"), helpdesk));
    }

    /** The {@code local} profile seeds the shipped fixture Users into their Groups. */
    @Test
    void theLocalProfileSeedsTheShippedFixturesIntoTheirGroups() {
        shipped("local").run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groups.findById(group("a003")).orElseThrow()
                        .hasMember(users.require("auditor").id()))
                .isTrue();
    }

    /**
     * APP_DEV_FIXTURES_EPIC_CLINICIAN, which {@code make fhircast-up} writes to {@code .env},
     * reaches the shipped document's {@code epic-clinician}.
     */
    @Test
    void theShippedDocumentTakesTheEpicClinicianFromItsEnvironmentVariable() {
        shipped("local").withPropertyValues("APP_DEV_FIXTURES_EPIC_CLINICIAN=" + CLINICIAN)
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of(CLINICIAN))).isPresent();
    }

    /** Unset, the shipped document seeds the Bootstrap Admin and the four fixture Users alone. */
    @Test
    void theShippedDocumentSeedsNoEpicClinicianByDefault() {
        shipped("local").run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.size()).isEqualTo(5);
    }

    /** A context that failed to start, its root cause naming {@code fragment}. */
    private static ContextConsumer<AssertableApplicationContext> refusesStartup(String fragment) {
        return context -> assertThat(context).getFailure().rootCause().hasMessageContaining(fragment);
    }

    private static UUID group(String suffix) {
        return UUID.fromString("00000000-0000-4000-8000-00000000" + suffix);
    }

    /**
     * A configured dormant fixture is seeded with the fixture password, backdated one day past
     * the revocation window and put into the Groups configured for it, so the startup dormancy
     * run that follows locks it and removes every one of those memberships.
     */
    @Test
    void theDormantFixtureIsSeededADayPastTheRevocationWindowInItsGroups() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.dormant-member=dormant",
                        "app.dev-fixtures.dormant-groups[0]=" + HELPDESK,
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        ScimUser dormant = users.require("dormant");
        assertThat(dormant.login().passwordHash()).isEqualTo("encoded:fixture-password");
        assertThat(dormant.dormancyBasis()).isEqualTo(ScimIdentities.NOW
                .minus(DormancyPolicy.DEFAULT_MEMBERSHIP_REVOCATION_WINDOW.plusDays(1)));
        assertThat(groups.findById(HELPDESK).orElseThrow().hasMember(dormant.id())).isTrue();
    }

    /** A dormant fixture with no Groups configured for it is seeded in none. */
    @Test
    void aDormantFixtureWithNoGroupsConfiguredJoinsNone() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.dormant-member=dormant",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groups.findGroupsOfUser(users.require("dormant").id())).isEmpty();
    }

    /** No dormant fixture configured: none is seeded. */
    @Test
    void noDormantFixtureIsSeededUnlessOneIsNamed() {
        local.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password",
                        "app.dev-fixtures.groups[0].id=" + HELPDESK,
                        "app.dev-fixtures.groups[0].display-name=Helpdesk",
                        "app.dev-fixtures.groups[0].member=helpdesk")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("dormant"))).isEmpty();
    }

    // ---- the fixtures are confined to the `local` profile --------------------------------------

    /**
     * The fixtures on with no profile active — a deployment that set APP_DEV_FIXTURES_ENABLED and
     * nothing else — fail startup naming the setting and the profile, before anything is seeded:
     * the fixtures waive the Bootstrap Admin's first-login change, so outside a developer's machine
     * they would ship a Superuser signing in on a configured first password.
     */
    @Test
    void enabledFixturesWithNoProfileActiveFailStartup() {
        contexts.withPropertyValues(
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("app.dev-fixtures.enabled (APP_DEV_FIXTURES_ENABLED) is true,"
                                + " but the 'local' profile is not active: the development"
                                + " fixtures run on a developer's machine only"));
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("admin"))).isEmpty();
    }

    /**
     * The review's case through the deployed {@code application.yaml}: a Bootstrap Admin password
     * set, APP_DEV_FIXTURES_ENABLED turned on, and no profile — refused.
     */
    @Test
    void theDeployedConfigurationRefusesTheFixturesWithNoProfileActive() {
        deployed().withPropertyValues(
                        "APP_BOOTSTRAP_PASSWORD=configured-password",
                        "APP_DEV_FIXTURES_ENABLED=true",
                        "APP_DEV_FIXTURES_PASSWORD=fixture-password")
                .run(refusesStartup("'local' profile is not active"));
    }

    /** A deployment's profile does not admit them either, nor does the {@code test} profile. */
    @Test
    void enabledFixturesUnderAnotherProfileFailStartup() {
        contexts.withPropertyValues(
                        "spring.profiles.active=prod,test",
                        "app.dev-fixtures.enabled=true",
                        "app.dev-fixtures.password=fixture-password")
                .run(refusesStartup("APP_DEV_FIXTURES_ENABLED"));
    }

    // ---- the Bootstrap Admin's first password has no fallback ---------------------------------

    /**
     * The deployed {@code application.yaml}, read from disk: the test resources' copy shadows it on
     * the classpath. Surefire drops {@code APP_BOOTSTRAP_PASSWORD} from the test environment, so
     * only what a test sets reaches it. Placeholders resolve strictly, as the running service's
     * auto-configured resolver has them: without it a context leaves an unresolvable one in place
     * as its literal text, which would seed that text as the password.
     */
    private ApplicationContextRunner deployed() {
        return directory
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=file:src/main/resources/application.yaml");
    }

    /**
     * A deployment that sets no APP_BOOTSTRAP_PASSWORD fails startup naming it, rather than seeding
     * a Superuser on a password this public repository publishes.
     */
    @Test
    void theDeployedConfigurationWithoutABootstrapPasswordFailsStartup() {
        deployed().run(refusesStartup("APP_BOOTSTRAP_PASSWORD"));
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("admin"))).isEmpty();
    }

    /** A blank one is refused as an absent one is, naming the setting and its variable. */
    @Test
    void aBlankBootstrapPasswordFailsStartup() {
        directory.withPropertyValues(
                        "app.auth.bootstrap-username=admin",
                        "app.auth.bootstrap-password= ")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("app.auth.bootstrap-password (APP_BOOTSTRAP_PASSWORD) must be"
                                + " set: it is the Bootstrap Admin's first password, and has no"
                                + " fallback"));
    }

    /**
     * With APP_BOOTSTRAP_PASSWORD set, the deployed configuration seeds the Bootstrap Admin on it,
     * under the username's {@code admin} fallback — a name, not a credential, so it stays.
     */
    @Test
    void theDeployedConfigurationSeedsTheBootstrapAdminOnTheConfiguredPassword() {
        deployed().withPropertyValues("APP_BOOTSTRAP_PASSWORD=configured-password")
                .run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.require("admin").login().passwordHash())
                .isEqualTo("encoded:configured-password");
    }

    /** Enabled with no password is refused rather than seeding Users with no usable credential. */
    @Test
    void enabledFixturesWithoutAPasswordFailStartup() {
        local.withPropertyValues(
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
