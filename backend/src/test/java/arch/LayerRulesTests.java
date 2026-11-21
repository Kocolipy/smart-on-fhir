package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules in {@link ArchitectureTest} that hold each layer's dependencies inward — the onion,
 * the declared infrastructure adapters, the layer-less roots the onion cannot see, the domain's
 * framework-free shape, and where transactions and JPA may appear — each shown reporting a planted
 * class and passing what it allows. The service's own classes comply, so the rules alone would
 * never be seen to fail.
 *
 * <p>Every one of these rules reads a class's package, so each planted class is written as
 * bytecode into a directory of its own by {@link PlantedClasses#named}: a class with one field per
 * type it reaches, which ArchUnit reads as a dependency on it whether or not the type exists.
 */
class LayerRulesTests {

    private static final String DOMAIN = "com.example.backend.scim.domain.";
    private static final String APPLICATION = "com.example.backend.scim.application.";
    private static final String CONTROLLER = "com.example.backend.scim.controller.";
    private static final String INFRASTRUCTURE = "com.example.backend.scim.infrastructure.";

    @TempDir
    Path planted;

    // ---- the onion ----------------------------------------------------------------------------

    /**
     * The onion's layer checks read each layer's incoming dependencies, so every class a planted
     * one reaches is planted too: a reached class that is not imported is in no layer.
     */
    @Test
    void a_dependency_pointing_outward_or_between_adapters_is_reported() {
        named(DOMAIN + "PlantedDomain").reaching(APPLICATION + "PlantedService")
                .plantUnder(planted);
        named(APPLICATION + "PlantedService").reaching(INFRASTRUCTURE + "http.PlantedClient")
                .plantUnder(planted);
        named(CONTROLLER + "PlantedController")
                .reaching(INFRASTRUCTURE + "persistence.PlantedAdapter").plantUnder(planted);
        named(INFRASTRUCTURE + "http.PlantedClient").plantUnder(planted);
        named(INFRASTRUCTURE + "persistence.PlantedAdapter").plantUnder(planted);

        assertThat(violations(ArchitectureTest.onion_architecture, planted))
                .contains("PlantedDomain.reached0> has type <" + APPLICATION + "PlantedService>")
                .contains("PlantedService.reached0> has type <" + INFRASTRUCTURE
                        + "http.PlantedClient>")
                .contains("PlantedController.reached0> has type <" + INFRASTRUCTURE
                        + "persistence.PlantedAdapter>");
    }

    @Test
    void dependencies_pointing_inward_are_not_reported() {
        named(CONTROLLER + "PlantedController").reaching(APPLICATION + "PlantedService")
                .plantUnder(planted);
        named(APPLICATION + "PlantedService").reaching(DOMAIN + "PlantedDomain")
                .plantUnder(planted);
        named(INFRASTRUCTURE + "persistence.PlantedAdapter").reaching(DOMAIN + "PlantedDomain")
                .plantUnder(planted);
        named(DOMAIN + "PlantedDomain").plantUnder(planted);

        assertThat(violations(ArchitectureTest.onion_architecture, planted)).isEmpty();
    }

    @Test
    void an_infrastructure_package_the_onion_does_not_name_is_reported() {
        named(INFRASTRUCTURE + "queue.PlantedAdapter").plantUnder(planted);
        named(INFRASTRUCTURE + "PlantedAdapter").plantUnder(planted);

        assertThat(violations(ArchitectureTest.every_infrastructure_package_is_a_declared_adapter,
                        planted))
                .contains(INFRASTRUCTURE + "queue.PlantedAdapter")
                .contains("<" + INFRASTRUCTURE + "PlantedAdapter>");
    }

    @Test
    void the_declared_infrastructure_adapters_are_not_reported() {
        for (String adapter : new String[] {
                "persistence", "session", "transaction", "request", "alert", "http.client"}) {
            named(INFRASTRUCTURE + adapter + ".PlantedAdapter").plantUnder(planted);
        }

        assertThat(violations(ArchitectureTest.every_infrastructure_package_is_a_declared_adapter,
                        planted))
                .isEmpty();
    }

    // ---- the layer-less roots -----------------------------------------------------------------

    @Test
    void an_inner_layer_reaching_the_epic_root_is_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .reaching("com.example.backend.auth.epic.EpicLogin").plantUnder(planted);
        named("com.example.backend.auth.domain.PlantedDomain")
                .reaching("com.example.backend.auth.epic.EpicLaunchValue").plantUnder(planted);

        assertThat(violations(ArchitectureTest.inner_layers_never_reach_the_epic_root, planted))
                .contains("PlantedService")
                .contains("PlantedDomain");
    }

    @Test
    void an_adapter_reaching_the_epic_root_and_an_inner_layer_reaching_its_own_are_not_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .reaching("com.example.backend.auth.epic.EpicLogin").plantUnder(planted);
        named("com.example.backend.auth.application.PlantedService")
                .reaching("com.example.backend.auth.domain.EpicTokenSet").plantUnder(planted);

        assertThat(violations(ArchitectureTest.inner_layers_never_reach_the_epic_root, planted))
                .isEmpty();
    }

    @Test
    void an_inner_layer_reaching_the_web_root_is_reported() {
        named(APPLICATION + "PlantedService")
                .reaching("com.example.backend.web.ApiExceptionHandler").plantUnder(planted);
        named(DOMAIN + "PlantedDomain").reaching("com.example.backend.web.ErrorNamespaces")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.inner_layers_never_reach_the_web_root, planted))
                .contains("PlantedService")
                .contains("PlantedDomain");
    }

    @Test
    void an_adapter_reaching_the_web_root_and_an_inner_layer_reaching_its_own_are_not_reported() {
        named(CONTROLLER + "PlantedController")
                .reaching("com.example.backend.web.ErrorNamespaces").plantUnder(planted);
        named(APPLICATION + "PlantedService").reaching(DOMAIN + "PlantedDomain")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.inner_layers_never_reach_the_web_root, planted))
                .isEmpty();
    }

    // ---- the framework-free domain ------------------------------------------------------------

    @Test
    void a_domain_class_reaching_a_framework_through_a_shared_type_is_reported() {
        named(DOMAIN + "PlantedDomain")
                .reaching("com.example.backend.observability.PlantedBridge").plantUnder(planted);
        named("com.example.backend.observability.PlantedBridge")
                .reaching("org.springframework.context.ApplicationContext").plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_domain_framework_dependencies_transitively,
                        planted))
                .contains("PlantedDomain")
                .contains("org.springframework.context.ApplicationContext");
    }

    @Test
    void a_domain_class_reaching_a_plain_shared_type_is_not_reported() {
        named(DOMAIN + "PlantedDomain")
                .reaching("com.example.backend.observability.PlantedBridge").plantUnder(planted);
        named("com.example.backend.observability.PlantedBridge").reaching("java.time.Instant")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_domain_framework_dependencies_transitively,
                        planted))
                .isEmpty();
    }

    @Test
    void a_domain_class_reaching_an_outer_layer_is_reported() {
        named(DOMAIN + "PlantedDomain").reaching(APPLICATION + "PlantedService",
                        INFRASTRUCTURE + "persistence.PlantedAdapter",
                        CONTROLLER + "PlantedController",
                        "com.example.backend.scim.config.PlantedConfig")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_domain_infrastructure_imports, planted))
                .contains(APPLICATION + "PlantedService")
                .contains(INFRASTRUCTURE + "persistence.PlantedAdapter")
                .contains(CONTROLLER + "PlantedController")
                .contains("com.example.backend.scim.config.PlantedConfig");
    }

    @Test
    void a_domain_class_reaching_the_domain_and_the_logging_base_is_not_reported() {
        named(DOMAIN + "PlantedDomain").reaching("com.example.backend.audit.domain.AuditTrail",
                        "com.example.backend.observability.LogEvent")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_domain_infrastructure_imports, planted))
                .isEmpty();
    }

    @Test
    void a_domain_or_entity_class_carrying_a_spring_stereotype_is_reported() {
        named(DOMAIN + "PlantedComponent")
                .annotatedWith("org.springframework.stereotype.Component").plantUnder(planted);
        named(DOMAIN + "PlantedService")
                .annotatedWith("org.springframework.stereotype.Service").plantUnder(planted);
        named(INFRASTRUCTURE + "persistence.entity.PlantedEntity")
                .annotatedWith("org.springframework.stereotype.Repository").plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_spring_annotations_in_domain, planted))
                .contains("PlantedComponent")
                .contains("PlantedService")
                .contains("PlantedEntity");
    }

    @Test
    void a_domain_mapper_carrying_a_stereotype_and_a_plain_domain_class_are_not_reported() {
        named(DOMAIN + "mapper.PlantedMapper")
                .annotatedWith("org.springframework.stereotype.Component").plantUnder(planted);
        named(DOMAIN + "PlantedDomain").plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_spring_annotations_in_domain, planted))
                .isEmpty();
    }

    // ---- web adapters delegate ----------------------------------------------------------------

    @Test
    void a_controller_reaching_persistence_directly_is_reported() {
        named(CONTROLLER + "PlantedController")
                .reaching(INFRASTRUCTURE + "persistence.ScimUserJpaRepository",
                        "com.example.backend.scim.repository.PlantedRepository")
                .plantUnder(planted);
        named("com.example.backend.scim.config.PlantedOutsideController")
                .annotatedWith("org.springframework.web.bind.annotation.RestController")
                .reaching(INFRASTRUCTURE + "persistence.ScimUserJpaRepository")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_business_logic_in_controllers, planted))
                .contains("ScimUserJpaRepository")
                .contains("PlantedRepository")
                .contains("PlantedOutsideController");
    }

    @Test
    void a_controller_reaching_a_domain_port_is_not_reported() {
        named(CONTROLLER + "PlantedController").reaching(DOMAIN + "ScimUserRepository")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_business_logic_in_controllers, planted))
                .isEmpty();
    }

    // ---- transactions at the use case ---------------------------------------------------------

    @Test
    void a_transactional_controller_or_domain_class_is_reported() {
        named(CONTROLLER + "PlantedController").annotatedWith(Transactional.class)
                .plantUnder(planted);
        named(DOMAIN + "PlantedDomain").annotatedWith(jakarta.transaction.Transactional.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_transactional_outside_service, planted))
                .contains("PlantedController")
                .contains("PlantedDomain");
    }

    @Test
    void a_transactional_use_case_class_is_not_reported() {
        named(APPLICATION + "PlantedService").annotatedWith(Transactional.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_transactional_outside_service, planted))
                .isEmpty();
    }

    @Test
    void a_transactional_handler_or_domain_method_is_reported() {
        named(CONTROLLER + "PlantedController")
                .withMethodAnnotatedWith("write", Transactional.class).plantUnder(planted);
        named(DOMAIN + "PlantedDomain")
                .withMethodAnnotatedWith("apply", jakarta.transaction.Transactional.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_transactional_handler_methods, planted))
                .contains("PlantedController.write()")
                .contains("PlantedDomain.apply()");
    }

    @Test
    void a_transactional_use_case_method_is_not_reported() {
        named(APPLICATION + "PlantedService")
                .withMethodAnnotatedWith("write", Transactional.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_transactional_handler_methods, planted))
                .isEmpty();
    }

    // ---- JPA in the persistence adapters ------------------------------------------------------

    @Test
    void the_jpa_stack_outside_a_persistence_adapter_is_reported() {
        named(APPLICATION + "PlantedService").reaching("jakarta.persistence.EntityManager",
                        "org.springframework.data.domain.Pageable", "org.hibernate.Session")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_jpa_outside_persistence_adapters, planted))
                .contains("jakarta.persistence.EntityManager")
                .contains("org.springframework.data.domain.Pageable")
                .contains("org.hibernate.Session");
    }

    @Test
    void the_jpa_stack_in_a_persistence_adapter_is_not_reported() {
        named(INFRASTRUCTURE + "persistence.PlantedAdapter")
                .reaching("jakarta.persistence.EntityManager",
                        "org.springframework.data.domain.Pageable", "org.hibernate.Session")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_jpa_outside_persistence_adapters, planted))
                .isEmpty();
    }
}
