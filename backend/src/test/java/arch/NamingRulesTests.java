package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * The naming and containment rules in {@link ArchitectureTest} — what a controller, service,
 * adapter, repository, entity, configuration and exception is called, and the package each lives
 * in — each shown reporting a planted class and passing a compliant one. The service's own classes
 * comply, so the rules alone would never be seen to fail.
 *
 * <p>The rules read a class's name, package, annotations and supertypes, so each planted class is
 * written as bytecode into a directory of its own by {@link PlantedClasses#named}.
 */
class NamingRulesTests {

    private static final String SCIM = "com.example.backend.scim.";

    @TempDir
    Path planted;

    // ---- names --------------------------------------------------------------------------------

    @Test
    void a_controller_not_named_as_one_is_reported() {
        named(SCIM + "controller.PlantedHandler").annotatedWith(RestController.class)
                .plantUnder(planted);
        named(SCIM + "controller.PlantedPage").annotatedWith(Controller.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_controller, planted))
                .contains("PlantedHandler")
                .contains("PlantedPage");
    }

    @Test
    void a_controller_named_as_one_is_not_reported() {
        named(SCIM + "controller.PlantedController").annotatedWith(RestController.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_controller, planted)).isEmpty();
    }

    @Test
    void a_use_case_bean_not_named_as_a_service_is_reported() {
        named(SCIM + "application.PlantedUseCase").annotatedWith(Service.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_service, planted))
                .contains("PlantedUseCase");
    }

    @Test
    void a_use_case_bean_named_as_a_service_is_not_reported() {
        named(SCIM + "application.PlantedService").annotatedWith(Service.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_service, planted)).isEmpty();
    }

    @Test
    void an_infrastructure_bean_not_named_as_an_adapter_is_reported() {
        named(SCIM + "infrastructure.persistence.PlantedStore").annotatedWith(Repository.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_adapter, planted))
                .contains("PlantedStore");
    }

    @Test
    void an_infrastructure_bean_named_as_an_adapter_is_not_reported() {
        named(SCIM + "infrastructure.persistence.PlantedAdapter").annotatedWith(Component.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_adapter, planted)).isEmpty();
    }

    @Test
    void a_spring_data_repository_not_named_as_one_is_reported() {
        named(SCIM + "infrastructure.persistence.PlantedStore").asInterface()
                .implementing(org.springframework.data.repository.Repository.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_jpa_repository, planted))
                .contains("PlantedStore");
    }

    @Test
    void a_spring_data_repository_named_as_one_is_not_reported() {
        named(SCIM + "infrastructure.persistence.PlantedJpaRepository").asInterface()
                .implementing(org.springframework.data.repository.Repository.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_jpa_repository, planted))
                .isEmpty();
    }

    @Test
    void an_entity_not_named_as_one_is_reported() {
        named(SCIM + "infrastructure.persistence.entity.PlantedRow")
                .annotatedWith(Entity.class, Table.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_entity, planted))
                .contains("PlantedRow");
    }

    @Test
    void an_entity_named_as_one_is_not_reported() {
        named(SCIM + "infrastructure.persistence.entity.PlantedEntity")
                .annotatedWith(Entity.class, Table.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_entity, planted)).isEmpty();
    }

    @Test
    void a_configuration_not_named_as_one_is_reported() {
        named(SCIM + "config.PlantedSetup").annotatedWith(Configuration.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_configuration, planted))
                .contains("PlantedSetup");
    }

    @Test
    void a_configuration_named_either_way_is_not_reported() {
        named(SCIM + "config.PlantedConfig").annotatedWith(Configuration.class).plantUnder(planted);
        named(SCIM + "config.PlantedConfiguration").annotatedWith(Configuration.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.naming_conventions_configuration, planted))
                .isEmpty();
    }

    @Test
    void an_exception_not_named_as_one_is_reported() {
        named(SCIM + "domain.PlantedFailure").extending("java.lang.RuntimeException")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.exception_naming_convention, planted))
                .contains("PlantedFailure");
    }

    @Test
    void an_exception_named_as_one_is_not_reported() {
        named(SCIM + "domain.PlantedException").extending("java.lang.IllegalStateException")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.exception_naming_convention, planted)).isEmpty();
    }

    @Test
    void a_class_named_as_an_exception_that_cannot_be_thrown_is_reported() {
        named(SCIM + "domain.PlantedException").plantUnder(planted);

        assertThat(violations(ArchitectureTest.exception_names_are_exceptions, planted))
                .contains("PlantedException");
    }

    @Test
    void a_throwable_named_as_an_exception_is_not_reported() {
        named(SCIM + "domain.PlantedException").extending("java.lang.IllegalStateException")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.exception_names_are_exceptions, planted)).isEmpty();
    }

    // ---- packages -----------------------------------------------------------------------------

    @Test
    void a_controller_outside_a_controller_package_is_reported() {
        named(SCIM + "application.PlantedController").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_controller, planted))
                .contains("PlantedController");
    }

    @Test
    void a_controller_in_a_controller_package_is_not_reported() {
        named(SCIM + "controller.PlantedController").plantUnder(planted);
        named("com.example.backend.auth.epic.controller.PlantedController").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_controller, planted)).isEmpty();
    }

    @Test
    void a_service_outside_an_application_package_is_reported() {
        named(SCIM + "domain.PlantedService").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_service, planted))
                .contains("PlantedService");
    }

    @Test
    void a_service_in_an_application_package_is_not_reported() {
        named(SCIM + "application.PlantedService").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_service, planted)).isEmpty();
    }

    @Test
    void an_adapter_outside_the_infrastructure_packages_is_reported() {
        named(SCIM + "application.PlantedAdapter").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_adapter, planted))
                .contains("PlantedAdapter");
    }

    @Test
    void an_adapter_in_an_infrastructure_package_is_not_reported() {
        named(SCIM + "infrastructure.http.PlantedAdapter").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_adapter, planted)).isEmpty();
    }

    @Test
    void a_spring_data_repository_outside_a_persistence_adapter_is_reported() {
        named(SCIM + "domain.PlantedJpaRepository").plantUnder(planted);
        named(SCIM + "infrastructure.http.HttpJpaRepository").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_jpa_repository, planted))
                .contains("PlantedJpaRepository")
                .contains("HttpJpaRepository");
    }

    @Test
    void a_spring_data_repository_in_a_persistence_adapter_is_not_reported() {
        named(SCIM + "infrastructure.persistence.PlantedJpaRepository").plantUnder(planted);

        assertThat(violations(ArchitectureTest.class_containment_jpa_repository, planted))
                .isEmpty();
    }

    @Test
    void a_configuration_outside_a_config_package_is_reported() {
        named(SCIM + "application.PlantedConfig").annotatedWith(Configuration.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.configuration_classes_in_config_package, planted))
                .contains("PlantedConfig");
    }

    @Test
    void a_configuration_in_a_config_package_is_not_reported() {
        named(SCIM + "config.PlantedConfig").annotatedWith(Configuration.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.configuration_classes_in_config_package, planted))
                .isEmpty();
    }

    @Test
    void an_entity_outside_an_entity_package_is_reported() {
        named(SCIM + "infrastructure.persistence.PlantedEntity")
                .annotatedWith(Entity.class, Table.class).plantUnder(planted);
        named(SCIM + "domain.DomainEntity").annotatedWith(Entity.class, Table.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.entities_only_in_entity_packages, planted))
                .contains("PlantedEntity")
                .contains("DomainEntity");
    }

    @Test
    void an_entity_in_an_entity_package_is_not_reported() {
        named(SCIM + "infrastructure.persistence.entity.PlantedEntity")
                .annotatedWith(Entity.class, Table.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.entities_only_in_entity_packages, planted))
                .isEmpty();
    }

    // ---- shapes the names imply ---------------------------------------------------------------

    @Test
    void a_repository_that_is_a_class_is_reported() {
        named(SCIM + "domain.PlantedRepository").plantUnder(planted);
        named(SCIM + "infrastructure.persistence.StoreRepository").plantUnder(planted);

        assertThat(violations(ArchitectureTest.repositories_must_be_interfaces, planted))
                .contains("PlantedRepository")
                .contains("StoreRepository");
    }

    @Test
    void a_repository_that_is_an_interface_is_not_reported() {
        named(SCIM + "domain.PlantedRepository").asInterface().plantUnder(planted);

        assertThat(violations(ArchitectureTest.repositories_must_be_interfaces, planted))
                .isEmpty();
    }

    @Test
    void a_controller_without_a_controller_annotation_is_reported() {
        named(SCIM + "controller.PlantedController").plantUnder(planted);

        assertThat(violations(ArchitectureTest.controllers_must_be_annotated, planted))
                .contains("PlantedController");
    }

    @Test
    void an_annotated_controller_and_a_controller_interface_are_not_reported() {
        named(SCIM + "controller.PlantedController").annotatedWith(Controller.class)
                .plantUnder(planted);
        named(SCIM + "controller.ContractController").asInterface().plantUnder(planted);

        assertThat(violations(ArchitectureTest.controllers_must_be_annotated, planted)).isEmpty();
    }
}
