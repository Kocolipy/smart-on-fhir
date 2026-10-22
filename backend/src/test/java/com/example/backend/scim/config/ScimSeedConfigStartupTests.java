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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(ScimSeedService.class, () -> new ScimSeedService(
                    users, groups, new InMemoryScimWriteLocks(), () -> { },
                    new RecordingAuditTrail(),
                    new MarkingPasswordEncoder(),
                    Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC), groupPermissions,
                    LoginStateModules.over(
                            users, Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC))))
            .withBean(DormancyPolicy.class, DormancyPolicy::defaults)
            .withUserConfiguration(ScimSeedConfig.class)
            .withPropertyValues(
                    "app.auth.bootstrap-username=admin",
                    "app.auth.bootstrap-password=admin-password");

    /**
     * Fixtures off: the Bootstrap Admin is the only User seeded — the baseline member, though
     * configured, is a fixture and is not — and startup succeeds with the Admin group alone.
     */
    @Test
    void withTheFixturesOffTheBootstrapAdminIsTheOnlyUserSeeded() {
        contexts.withPropertyValues("app.dev-fixtures.baseline-member=user")
                .run(context -> assertThatCode(
                        () -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS))
                        .doesNotThrowAnyException());

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("admin"))).isPresent();
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("user"))).isEmpty();
        assertThat(groups.findById(TestGroupPermissions.SUPERUSER_GROUP_ID)).isPresent();
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

    /** Fixtures off: the Bootstrap Admin owes its first-login change, as in a deployment. */
    @Test
    void withTheFixturesOffTheBootstrapAdminOwesAPasswordChange() {
        contexts.run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(users.require("admin").login().isPasswordChangeRequired()).isTrue();
    }

    /** Fixtures on: a developer's Bootstrap Admin signs straight in. */
    @Test
    void withTheFixturesOnTheBootstrapAdminOwesNoPasswordChange() {
        contexts.withPropertyValues(
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

    /** The fixture Group Permissions apply after the fixture Groups, so the Group they name exists. */
    @Test
    void theFixtureGroupPermissionsApplyToTheGroupTheFixturesCreate() {
        contexts.withPropertyValues(
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

        assertThat(groupPermissions.carriedBy(List.of(
                        group("a001"), group("a002"), group("a003"), group("a004"), group("a005"),
                        group("a006"), group("a007"))))
                .isEqualTo(Map.of(
                        group("a002"), Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK,
                                Permission.GROUP_READ),
                        group("a003"), Set.of(Permission.AUDIT_READ),
                        group("a004"), Set.of(Permission.CONNECTOR_READ),
                        group("a005"), Set.of(Permission.OPS_READ),
                        group("a006"), Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK),
                        group("a007"), Set.of(Permission.AUDIT_READ)));
    }

    /** Any other profile seeds the same Groups and no Group Permission at all. */
    @Test
    void anotherProfileSeedsNoGroupPermission() {
        shipped("dev").run(context -> context.getBean(ApplicationRunner.class).run(NO_ARGUMENTS));

        assertThat(groups.findById(group("a002"))).isPresent();
        assertThat(groupPermissions.findAll()).isEmpty();
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
        contexts.withPropertyValues(
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
        contexts.withPropertyValues(
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
