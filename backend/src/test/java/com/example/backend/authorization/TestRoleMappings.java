package com.example.backend.authorization;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Role mappings for tests that construct services by hand.
 *
 * <p>{@link #superuserOnly()} is the same mapping the test resources' {@code application.yaml}
 * configures, under the same Superuser Group id, so a hand-built service and a Spring context agree.
 */
public final class TestRoleMappings {

    /** The Superuser Group id the test configuration and the development mapping both use. */
    public static final UUID SUPERUSER_GROUP_ID =
            UUID.fromString("00000000-0000-4000-8000-00000000a001");

    /** Every Permission's name, in declaration order. */
    public static final List<String> EVERY_PERMISSION =
            Arrays.stream(Permission.values()).map(Permission::value).toList();

    /**
     * What a Superuser's session holds: baseline access, {@code ROLE_SUPERUSER} and every
     * Permission. For a test that builds a session by hand rather than signing in.
     */
    public static final String[] SUPERUSER_AUTHORITIES = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("ROLE_USER", "ROLE_SUPERUSER"),
                    EVERY_PERMISSION.stream())
            .toArray(String[]::new);

    private TestRoleMappings() {
    }

    /** One Role holding every Permission, conferred by the Superuser Group alone. */
    public static RoleMapping superuserOnly() {
        return RoleMapping.of(
                List.of(new RoleDefinition("Superuser", EVERY_PERMISSION)),
                List.of(new GroupAssignment(SUPERUSER_GROUP_ID, "Superuser", true)));
    }
}
