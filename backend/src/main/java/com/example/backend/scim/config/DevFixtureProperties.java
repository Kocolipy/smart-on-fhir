package com.example.backend.scim.config;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The development fixtures, bound from {@code app.dev-fixtures} in {@code authorization.yaml}:
 * off unless {@code APP_DEV_FIXTURES_ENABLED} is set, with a password that has no published
 * fallback.
 *
 * @param enabled        whether to seed the fixtures at all
 * @param password       every fixture User's password
 * @param baselineMember the userName of a User in no Group, holding only the baseline
 *                       Permissions; {@code null} for none
 * @param groups         one Group per non-Superuser Role, each with one User in it
 * @param dormantMember  the userName of a User whose dormancy basis is backdated past the
 *                       revocation window at every startup, so the development profile's startup
 *                       dormancy run locks it and removes its memberships; {@code null} for none
 * @param dormantGroups  the Groups the dormant member is put back into at every startup, for that
 *                       run to remove; {@code null} or empty for none
 */
@ConfigurationProperties("app.dev-fixtures")
public record DevFixtureProperties(
        boolean enabled,
        String password,
        String baselineMember,
        List<GroupFixture> groups,
        String dormantMember,
        List<UUID> dormantGroups) {

    /**
     * One Group fixture.
     *
     * @param id          the Group's fixed stable id, the one the role mapping names
     * @param displayName its display name
     * @param member      the userName of its one member
     */
    public record GroupFixture(UUID id, String displayName, String member) {
    }
}
