package com.example.backend.scim.config;

import com.example.backend.scim.application.ScimSeedService;
import com.example.backend.scim.application.ScimSeedService.DevFixture;
import com.example.backend.scim.application.ScimSeedService.GroupPermissionFixture;
import com.example.backend.scim.application.ScimSeedService.SeededIdentity;
import com.example.backend.scim.domain.DormancyPolicy;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Runs the directory's seeding at startup, so a fresh deployment has a recovery path before it
 * serves a request.
 *
 * <p>{@code app.auth.bootstrap-username} / {@code bootstrap-password} ({@code APP_BOOTSTRAP_*})
 * name the deployment's recovery identity, the one configured identity every deployment gets. The
 * password has no fallback, so a deployment still setting the former {@code APP_SECONDARY_*}
 * variables must rename them, or it fails startup for want of {@code APP_BOOTSTRAP_PASSWORD}.
 *
 * <p>This file is the only place the configured name is turned into a privilege: seeding writes
 * the reservation marker on the Bootstrap Admin's row, and nothing downstream compares a username
 * to decide anything.
 *
 * <p>Seeding reads the clock through the {@code Clock} bean rather than {@code Instant.now()}, so
 * what a seeded resource records as its creation time is assertable. That bean is declared once, by
 * {@code LoginLockoutConfig}, and shared — a second one here would leave the container with two
 * candidates and fail startup.
 */
@Configuration
@EnableConfigurationProperties(DevFixtureProperties.class)
public class ScimSeedConfig {

    /**
     * The one profile that admits the development fixtures: a developer's run. {@code
     * authorization.yaml} activates the fixture Group Permissions under the same name, so the two
     * are renamed together.
     */
    static final String LOCAL_PROFILE = "local";

    /**
     * Seeding, then the development fixtures when enabled — their Group Permissions after the
     * Groups, and the dormant fixture last of them — then the two checks: that the reserved Admin
     * group exists, and that every stored Group Permission names one a Group may carry. In that
     * order, in one runner, so the checks see exactly what startup wrote and a failure of any step
     * fails startup. A Group deleted over SCIM takes its Permissions with it, so it leaves nothing
     * behind for a check to refuse.
     *
     * <p>The development fixtures are refused outside the {@code local} profile, here, as the
     * runner is created, so a context that would seed them anywhere else fails to start before any
     * runner — the startup dormancy run the fixtures enable included — touches the directory. They
     * waive the Bootstrap Admin's first-login change and seed Users on one shared password, so a
     * deployment that set {@code APP_DEV_FIXTURES_ENABLED} would otherwise ship a Superuser that
     * never has to replace its configured first password. {@code local} is the one profile no
     * deployment runs: it migrates in-process, which the deployed service never does. The tests
     * that seed the fixtures activate it too, so no other profile, {@code test} included, admits
     * them.
     */
    @Bean
    ApplicationRunner seedScimDirectory(
            ScimSeedService seeding,
            DevFixtureProperties devFixtures,
            DormancyPolicy dormancy,
            Environment environment,
            @Value("${app.auth.bootstrap-username}") String recoveryUserName,
            @Value("${app.auth.bootstrap-password}") String recoveryPassword) {
        if (devFixtures.enabled() && !environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE))) {
            throw new IllegalStateException(
                    "app.dev-fixtures.enabled (APP_DEV_FIXTURES_ENABLED) is true, but the '"
                            + LOCAL_PROFILE + "' profile is not active: the development"
                            + " fixtures run on a developer's machine only");
        }
        // No fallback in application.yaml, so an unset APP_BOOTSTRAP_PASSWORD already fails as an
        // unresolvable placeholder naming it; a set but blank one is refused here the same way.
        if (recoveryPassword.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.bootstrap-password (APP_BOOTSTRAP_PASSWORD) must be set: it is the"
                            + " Bootstrap Admin's first password, and has no fallback");
        }
        return arguments -> {
            // The fixtures are on only under `local` (refused above), so the Bootstrap Admin's
            // first-login change is waived only on a developer's machine.
            seeding.seed(new SeededIdentity(recoveryUserName, recoveryPassword),
                    !devFixtures.enabled());
            if (devFixtures.enabled()) {
                seeding.seedDevFixtures(
                        devFixtures.groups() == null ? List.of() : devFixtures.groups().stream()
                                .map(group -> new DevFixture(
                                        group.id(), group.displayName(), group.member()))
                                .toList(),
                        devFixtures.baselineMember(),
                        devFixtures.password());
                seeding.seedGroupPermissionFixtures(devFixtures.groupPermissions() == null
                        ? List.of() : devFixtures.groupPermissions().stream()
                                .map(fixture -> new GroupPermissionFixture(
                                        fixture.group(), fixture.permissions()))
                                .toList());
                if (devFixtures.dormantMember() != null) {
                    // A day past the revocation window, so the startup dormancy run locks it
                    // and removes every membership it was put back into.
                    seeding.seedDormantDevFixture(devFixtures.dormantMember(),
                            devFixtures.password(),
                            dormancy.membershipRevocationWindow().plusDays(1),
                            devFixtures.dormantGroups() == null
                                    ? List.of() : devFixtures.dormantGroups());
                }
            }
            seeding.verifyAdminGroup();
            seeding.verifyGroupPermissions();
        };
    }
}
