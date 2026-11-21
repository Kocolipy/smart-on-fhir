package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The coding rules in {@link ArchitectureTest} — no cycles, injection through constructors,
 * named exceptions, SLF4J logging, no test library or deprecated type in production, and the
 * entity mapping's shape — each shown reporting a planted class and passing a compliant one. The
 * service's own classes comply, so the rules alone would never be seen to fail.
 *
 * <p>Each planted class is written as bytecode into a directory of its own by
 * {@link PlantedClasses#named}, with exactly the dependency, call, field or annotation a case
 * needs.
 */
class CodingRulesTests {

    private static final String SCIM = "com.example.backend.scim.";

    @TempDir
    Path planted;

    // ---- cycles -------------------------------------------------------------------------------

    @Test
    void a_cycle_between_two_modules_is_reported() {
        named(SCIM + "domain.PlantedUser").reaching("com.example.backend.audit.domain.PlantedEvent")
                .plantUnder(planted);
        named("com.example.backend.audit.domain.PlantedEvent").reaching(SCIM + "domain.PlantedUser")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_cyclic_dependencies, planted))
                .contains("Cycle detected")
                .contains("PlantedUser")
                .contains("PlantedEvent");
    }

    @Test
    void a_one_way_edge_between_modules_and_a_cycle_inside_one_are_not_reported() {
        named(SCIM + "application.PlantedService").reaching("com.example.backend.audit.domain.X")
                .plantUnder(planted);
        named(SCIM + "domain.PlantedUser").reaching(SCIM + "application.PlantedService")
                .plantUnder(planted);
        named(SCIM + "application.PlantedService$Step").reaching(SCIM + "domain.PlantedUser")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_cyclic_dependencies, planted)).isEmpty();
    }

    @Test
    void a_cycle_between_two_packages_of_one_module_is_reported() {
        named(SCIM + "domain.PlantedUser").reaching(SCIM + "application.PlantedService")
                .plantUnder(planted);
        named(SCIM + "application.PlantedService").reaching(SCIM + "domain.PlantedUser")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_package_cycles, planted))
                .contains("Cycle detected")
                .contains("PlantedUser")
                .contains("PlantedService");
    }

    @Test
    void a_cycle_inside_one_package_is_not_reported() {
        named(SCIM + "domain.PlantedUser").reaching(SCIM + "domain.PlantedGroup")
                .plantUnder(planted);
        named(SCIM + "domain.PlantedGroup").reaching(SCIM + "domain.PlantedUser")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_package_cycles, planted)).isEmpty();
    }

    // ---- injection ----------------------------------------------------------------------------

    @Test
    void an_injected_field_is_reported() {
        named(SCIM + "application.PlantedService")
                .withFieldAnnotatedWith(SCIM + "domain.ScimUserRepository",
                        "org.springframework.beans.factory.annotation.Autowired")
                .withFieldAnnotatedWith("java.lang.String",
                        "org.springframework.beans.factory.annotation.Value")
                .withFieldAnnotatedWith(SCIM + "domain.ScimGroupRepository",
                        "jakarta.inject.Inject")
                .withFieldAnnotatedWith("java.time.Clock", "jakarta.annotation.Resource")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_field_injection, planted))
                .contains("reached0").contains("reached1").contains("reached2")
                .contains("reached3");
    }

    @Test
    void a_field_set_by_the_constructor_is_not_reported() {
        named(SCIM + "application.PlantedService").reaching(SCIM + "domain.ScimUserRepository")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_field_injection, planted)).isEmpty();
    }

    @Test
    void a_service_constructing_a_collaborator_is_reported() {
        named(SCIM + "application.PlantedService")
                .constructing(SCIM + "application.OtherService")
                .constructing(SCIM + "domain.PlantedRepository")
                .constructing(SCIM + "infrastructure.persistence.PlantedAdapter")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_new_inside_service, planted))
                .contains("OtherService.<init>()")
                .contains("PlantedRepository.<init>()")
                .contains("PlantedAdapter.<init>()");
    }

    @Test
    void a_service_constructing_a_value_and_a_configuration_constructing_a_service_are_not_reported() {
        named(SCIM + "application.PlantedService").constructing(SCIM + "domain.PlantedValue")
                .plantUnder(planted);
        named(SCIM + "config.PlantedConfig").constructing(SCIM + "application.OtherService")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_new_inside_service, planted)).isEmpty();
    }

    // ---- exceptions ---------------------------------------------------------------------------

    @Test
    void a_method_declaring_a_generic_exception_is_reported() {
        named(SCIM + "application.PlantedService")
                .withMethodThrowing("load", "java.lang.Exception")
                .withMethodThrowing("run", "java.lang.Throwable")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_generic_exception_throws, planted))
                .contains("PlantedService.load()")
                .contains("PlantedService.run()");
    }

    @Test
    void a_method_declaring_a_named_exception_is_not_reported() {
        named(SCIM + "application.PlantedService")
                .withMethodThrowing("load", "java.io.IOException").plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_generic_exception_throws, planted)).isEmpty();
    }

    @Test
    void throwing_a_generic_exception_is_reported() {
        named(SCIM + "application.PlantedService")
                .constructing("java.lang.RuntimeException")
                .constructing("java.lang.Exception")
                .constructing("java.lang.Throwable")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_generic_exceptions_thrown, planted))
                .contains("RuntimeException.<init>()")
                .contains("Exception.<init>()")
                .contains("Throwable.<init>()");
    }

    @Test
    void throwing_a_named_exception_is_not_reported() {
        named(SCIM + "application.PlantedService").constructing("java.lang.IllegalStateException")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_generic_exceptions_thrown, planted)).isEmpty();
    }

    // ---- logging and the standard streams -----------------------------------------------------

    @Test
    void writing_to_the_standard_streams_is_reported() {
        named(SCIM + "application.PlantedService")
                .readingStatic("java.lang.System", "out", "java.io.PrintStream")
                .readingStatic("java.lang.System", "err", "java.io.PrintStream")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_system_out, planted))
                .contains("System.out")
                .contains("System.err");
    }

    @Test
    void reading_another_system_value_is_not_reported() {
        named(SCIM + "application.PlantedService")
                .calling("java.lang.System", "lineSeparator", "()Ljava/lang/String;")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_system_out, planted)).isEmpty();
    }

    @Test
    void logging_through_java_util_logging_with_or_without_a_stored_logger_is_reported() {
        named(SCIM + "application.PlantedService")
                .calling("java.util.logging.Logger", "getLogger",
                        "(Ljava/lang/String;)Ljava/util/logging/Logger;")
                .plantUnder(planted);
        named(SCIM + "application.LoggerHolder").reaching("java.util.logging.Logger")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_java_util_logging, planted))
                .contains("PlantedService.planted0()> calls method <java.util.logging.Logger.getLogger")
                .contains("LoggerHolder.reached0> has type <java.util.logging.Logger>");
    }

    @Test
    void logging_through_slf4j_is_not_reported() {
        named(SCIM + "application.PlantedService")
                .calling("org.slf4j.LoggerFactory", "getLogger",
                        "(Ljava/lang/String;)Lorg/slf4j/Logger;")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_java_util_logging, planted)).isEmpty();
    }

    // ---- what production code reaches ---------------------------------------------------------

    @Test
    void production_code_reaching_a_test_library_is_reported() {
        named(SCIM + "application.PlantedService").reaching("org.junit.jupiter.api.Assertions",
                        "org.mockito.Mockito", "org.testng.Assert")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_test_imports_in_production, planted))
                .contains("org.junit.jupiter.api.Assertions")
                .contains("org.mockito.Mockito")
                .contains("org.testng.Assert");
    }

    @Test
    void a_test_package_reaching_a_test_library_is_not_reported() {
        named(SCIM + "test.PlantedSupport").reaching("org.junit.jupiter.api.Assertions")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_test_imports_in_production, planted)).isEmpty();
    }

    @Test
    void reaching_a_deprecated_type_is_reported() {
        named(SCIM + "domain.PlantedRetired").annotatedWith("java.lang.Deprecated")
                .plantUnder(planted);
        named(SCIM + "application.PlantedService").reaching(SCIM + "domain.PlantedRetired")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_dependency_on_deprecated, planted))
                .contains("PlantedService");
    }

    @Test
    void reaching_a_current_type_is_not_reported() {
        named(SCIM + "domain.PlantedCurrent").plantUnder(planted);
        named(SCIM + "application.PlantedService").reaching(SCIM + "domain.PlantedCurrent")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_dependency_on_deprecated, planted)).isEmpty();
    }

    // ---- the entity mapping -------------------------------------------------------------------

    private static final String PLANTED_ENTITY =
            SCIM + "infrastructure.persistence.entity.PlantedEntity";

    @Test
    void an_entity_without_a_table_mapping_is_reported() {
        named(PLANTED_ENTITY).annotatedWith(Entity.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.entities_have_required_annotations, planted))
                .contains("PlantedEntity");
    }

    @Test
    void an_entity_with_a_table_mapping_is_not_reported() {
        named(PLANTED_ENTITY).annotatedWith(Entity.class, Table.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.entities_have_required_annotations, planted))
                .isEmpty();
    }

    @Test
    void a_controller_reaching_an_entity_is_reported() {
        named(PLANTED_ENTITY).annotatedWith(Entity.class, Table.class).plantUnder(planted);
        named(SCIM + "controller.PlantedController")
                .annotatedWith("org.springframework.web.bind.annotation.RestController")
                .reaching(PLANTED_ENTITY).plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_entity_in_controllers, planted))
                .contains("PlantedController");
    }

    @Test
    void a_controller_reaching_a_projection_is_not_reported() {
        named(SCIM + "application.PlantedProjection").plantUnder(planted);
        named(SCIM + "controller.PlantedController")
                .annotatedWith("org.springframework.web.bind.annotation.RestController")
                .reaching(SCIM + "application.PlantedProjection").plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_entity_in_controllers, planted)).isEmpty();
    }
}
