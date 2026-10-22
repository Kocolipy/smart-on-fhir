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

/**
 * Runs the directory's seeding at startup, so a fresh deployment has a recovery path before it
 * serves a request.
 *
 * <p>{@code app.auth.bootstrap-username} / {@code bootstrap-password} ({@code APP_BOOTSTRAP_*})
 * name the deployment's recovery identity, the one configured identity every deployment gets. A
 * deployment still setting the former {@code APP_SECONDARY_*} variables must rename them, or it
 * seeds the {@code application.yaml} fallback instead.
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
     * Seeding, then the development fixtures when enabled — their Group Permissions after the
     * Groups, and the dormant fixture last of them — then the two checks: that the reserved Admin
     * group exists, and that every stored Group Permission names one a Group may carry. In that
     * order, in one runner, so the checks see exactly what startup wrote and a failure of any step
     * fails startup. A Group deleted over SCIM takes its Permissions with it, so it leaves nothing
     * behind for a check to refuse.
     */
    @Bean
    ApplicationRunner seedScimDirectory(
            ScimSeedService seeding,
            DevFixtureProperties devFixtures,
            DormancyPolicy dormancy,
            @Value("${app.auth.bootstrap-username}") String recoveryUserName,
            @Value("${app.auth.bootstrap-password}") String recoveryPassword) {
        return arguments -> {
            // Development fixtures are never on in a deployment, so the Bootstrap Admin's
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
