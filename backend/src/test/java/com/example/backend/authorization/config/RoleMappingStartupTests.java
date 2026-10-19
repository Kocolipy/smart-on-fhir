package com.example.backend.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.authorization.domain.InvalidRoleMappingException;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.RoleMapping;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/**
 * That the role mapping reaches the application from the {@code app.authorization} block, and that
 * each invalid mapping stops startup with a message naming the problem.
 *
 * <p>Driven through a real context refresh with the configuration bound as a deployment's is, one
 * test per refusal the issue lists. {@code RoleMappingTests} covers the rules themselves. A Group
 * id that does not resolve needs the directory, so its startup test is
 * {@code ScimSeedConfigStartupTests}.
 */
class RoleMappingStartupTests {

    private static final String ADMINS = "00000000-0000-4000-8000-0000000000a1";
    private static final String HELPDESK = "00000000-0000-4000-8000-0000000000a2";

    /**
     * Every Permission as one comma-separated value: relaxed binding splits it into the list
     * exactly as it does a deployment's environment variable.
     */
    private static final String EVERY = Arrays.stream(Permission.values())
            .map(Permission::value)
            .collect(Collectors.joining(","));

    private static final List<String> SUPERUSER_ROLE = List.of(
            "app.authorization.roles[0].name=Superuser",
            "app.authorization.roles[0].permissions=" + EVERY);

    private static final List<String> SUPERUSER_GROUP = List.of(
            "app.authorization.groups[0].id=" + ADMINS,
            "app.authorization.groups[0].role=Superuser",
            "app.authorization.groups[0].superuser=true");

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(RoleMappingConfig.class);

    @Test
    void aValidMappingStartsAndIsPublishedAsTheRoleMapping() {
        contexts.withPropertyValues(properties(SUPERUSER_ROLE, SUPERUSER_GROUP, List.of(
                        "app.authorization.roles[1].name=Helpdesk",
                        "app.authorization.roles[1].permissions=user:read,user:write",
                        "app.authorization.groups[1].id=" + HELPDESK,
                        "app.authorization.groups[1].role=Helpdesk")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoleMapping mapping = context.getBean(RoleMapping.class);
                    assertThat(mapping.superuserGroupId()).isEqualTo(UUID.fromString(ADMINS));
                    assertThat(mapping.permissionsOf(List.of(UUID.fromString(HELPDESK))))
                            .containsExactlyInAnyOrder(Permission.USER_READ, Permission.USER_WRITE);
                    assertThat(mapping.permissionsOf(List.of(UUID.fromString(ADMINS))))
                            .containsExactlyInAnyOrder(Permission.values());
                });
    }

    @Test
    void anUnknownPermissionFailsStartup() {
        assertStartupFails(
                "Role 'Helpdesk' names unknown Permission 'user:delete'",
                SUPERUSER_ROLE, SUPERUSER_GROUP, List.of(
                        "app.authorization.roles[1].name=Helpdesk",
                        "app.authorization.roles[1].permissions=user:delete"));
    }

    @Test
    void anUnknownRoleFailsStartup() {
        assertStartupFails(
                "Group " + HELPDESK + " is mapped to unknown Role 'Helpdesk'",
                SUPERUSER_ROLE, SUPERUSER_GROUP, List.of(
                        "app.authorization.groups[1].id=" + HELPDESK,
                        "app.authorization.groups[1].role=Helpdesk"));
    }

    @Test
    void aDuplicateGroupIdFailsStartup() {
        assertStartupFails(
                "Group " + ADMINS + " is mapped more than once",
                SUPERUSER_ROLE, SUPERUSER_GROUP, List.of(
                        "app.authorization.groups[1].id=" + ADMINS,
                        "app.authorization.groups[1].role=Superuser"));
    }

    @Test
    void noSuperuserGroupFailsStartup() {
        assertStartupFails(
                "no Superuser Group is designated",
                SUPERUSER_ROLE, List.of(
                        "app.authorization.groups[0].id=" + ADMINS,
                        "app.authorization.groups[0].role=Superuser"));
    }

    /** No block at all is the same refusal: there is no mapping to fall back to. */
    @Test
    void anAbsentMappingFailsStartup() {
        assertStartupFails("no Superuser Group is designated");
    }

    @Test
    void moreThanOneSuperuserGroupFailsStartup() {
        assertStartupFails(
                "more than one Superuser Group is designated: " + ADMINS + ", " + HELPDESK,
                SUPERUSER_ROLE, SUPERUSER_GROUP, List.of(
                        "app.authorization.groups[1].id=" + HELPDESK,
                        "app.authorization.groups[1].role=Superuser",
                        "app.authorization.groups[1].superuser=true"));
    }

    @Test
    void aSuperuserRoleMissingAPermissionFailsStartup() {
        String allButOpsRead = Arrays.stream(Permission.values())
                .filter(permission -> permission != Permission.OPS_READ)
                .map(Permission::value)
                .collect(Collectors.joining(","));

        assertStartupFails(
                "Superuser Group " + ADMINS + "'s Role 'Superuser' is missing Permissions:"
                        + " ops:read",
                SUPERUSER_GROUP, List.of(
                        "app.authorization.roles[0].name=Superuser",
                        "app.authorization.roles[0].permissions=" + allButOpsRead));
    }

    /**
     * The shipped development mapping is itself valid — five mapped Groups, one of them the
     * Superuser Group — so a run on it fails only for a Group that does not resolve, never for its
     * own shape. Read from the shipped document itself, not a copy.
     */
    @Test
    void theShippedDevelopmentMappingIsValid() {
        contexts.withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("authorization.yaml",
                                        new ClassPathResource("authorization.yaml"))
                                .forEach(source -> context.getEnvironment().getPropertySources()
                                        .addFirst(source));
                    } catch (java.io.IOException unreadable) {
                        throw new IllegalStateException(unreadable);
                    }
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoleMapping mapping = context.getBean(RoleMapping.class);
                    assertThat(mapping.superuserGroupId()).isEqualTo(
                            UUID.fromString("00000000-0000-4000-8000-00000000a001"));
                    assertThat(mapping.mappedGroupIds()).hasSize(5);
                });
    }

    @SafeVarargs
    private void assertStartupFails(String problem, List<String>... blocks) {
        contexts.withPropertyValues(properties(blocks))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(InvalidRoleMappingException.class)
                        .hasMessage("Invalid role mapping: " + problem));
    }

    @SafeVarargs
    private static String[] properties(List<String>... blocks) {
        List<String> all = new ArrayList<>();
        Stream.of(blocks).forEach(all::addAll);
        return all.toArray(String[]::new);
    }
}
