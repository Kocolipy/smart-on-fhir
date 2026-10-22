package com.example.backend.authorization;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.application.ScimSeedService;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Group Permissions for tests that construct services by hand, and what a Superuser's session
 * holds.
 *
 * <p>{@link #superuserOnly()} is what every test context starts with: the seeded Admin group, whose
 * members are the Superuser, and no Group carrying any Permission, so a hand-built service and a
 * Spring context agree.
 */
public final class TestGroupPermissions {

    /** The id the seed creates the Admin group under, in every context and in local runs. */
    public static final UUID SUPERUSER_GROUP_ID = ScimSeedService.ADMIN_GROUP_ID;

    /**
     * Every Permission a session may hold — the delegatable ones and the baseline, all but the
     * token-only ones — in declaration order.
     */
    public static final List<String> EVERY_PERMISSION = Arrays.stream(Permission.values())
            .filter(permission -> !permission.tokenOnly())
            .map(Permission::value)
            .toList();

    /**
     * What a Superuser's session holds: baseline access, {@code ROLE_SUPERUSER} and every
     * Permission a session may hold. For a test that builds a session by hand rather than signing in.
     */
    public static final String[] SUPERUSER_AUTHORITIES = Stream.concat(
                    Stream.of("ROLE_USER", "ROLE_SUPERUSER"), EVERY_PERMISSION.stream())
            .toArray(String[]::new);

    private TestGroupPermissions() {
    }

    /** The Admin group's members are the Superuser; no Group carries a Permission. */
    public static InMemoryGroupPermissions superuserOnly() {
        return InMemoryGroupPermissions.superuserOnly();
    }
}
