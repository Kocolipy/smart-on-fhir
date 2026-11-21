package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.annotation.security.RolesAllowed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The method-security rules in {@link ArchitectureTest} (ADR 0015), each shown reporting a planted
 * class and passing the shape it allows — the service's own handlers comply, so the rules alone
 * would never be seen to fail.
 *
 * <p>The rules read a handler's annotations and its controller's name and package, so each planted
 * class is written as bytecode into a directory of its own by {@link PlantedClasses#named}.
 */
class PermissionRulesTests {


    @TempDir
    Path planted;

    // ---- one mechanism ------------------------------------------------------------------------

    @Test
    void a_method_secured_other_than_by_pre_authorize_is_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("postAuthorized", PostAuthorize.class)
                .withMethodAnnotatedWith("secured", Secured.class)
                .withMethodAnnotatedWith("rolesAllowed", RolesAllowed.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.one_method_security_mechanism, planted))
                .contains("PlantedController.postAuthorized()")
                .contains("PlantedController.secured()")
                .contains("PlantedController.rolesAllowed()");
    }

    @Test
    void a_method_secured_by_pre_authorize_is_not_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", PreAuthorize.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.one_method_security_mechanism, planted)).isEmpty();
    }

    // ---- declared on handlers -----------------------------------------------------------------

    @Test
    void a_permission_declared_below_the_web_adapter_is_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .annotatedWith(Service.class)
                .withMethodAnnotatedWith("decide", PreAuthorize.class)
                .plantUnder(planted);
        named("com.example.backend.auth.config.PlantedOutsideController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", PreAuthorize.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.permissions_are_declared_on_handlers, planted))
                .contains("PlantedService.decide()")
                .contains("PlantedOutsideController.read()");
    }

    @Test
    void a_permission_declared_on_a_controller_packages_handler_is_not_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", PreAuthorize.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.permissions_are_declared_on_handlers, planted))
                .isEmpty();
    }

    // ---- never class-wide ---------------------------------------------------------------------

    @Test
    void a_class_wide_security_declaration_is_reported() {
        named("com.example.backend.auth.controller.PreAuthorizedController")
                .annotatedWith(RestController.class, PreAuthorize.class).plantUnder(planted);
        named("com.example.backend.auth.controller.SecuredController")
                .annotatedWith(RestController.class, Secured.class).plantUnder(planted);
        named("com.example.backend.auth.controller.RolesAllowedController")
                .annotatedWith(RestController.class, RolesAllowed.class).plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_class_level_method_security, planted))
                .contains("PreAuthorizedController")
                .contains("SecuredController")
                .contains("RolesAllowedController");
    }

    @Test
    void a_declaration_on_each_handler_is_not_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", PreAuthorize.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_class_level_method_security, planted)).isEmpty();
    }

    // ---- every protected handler declares one -------------------------------------------------

    @Test
    void a_handler_of_a_protected_controller_without_a_permission_is_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", GetMapping.class)
                .withMethodAnnotatedWith("write", PostMapping.class)
                .withMethodAnnotatedWith("map", RequestMapping.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.every_protected_handler_declares_a_permission,
                        planted))
                .contains("PlantedController.read()")
                .contains("PlantedController.write()")
                .contains("PlantedController.map()");
    }

    @Test
    void a_handler_that_declares_its_permission_is_not_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", GetMapping.class, PreAuthorize.class)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.every_protected_handler_declares_a_permission,
                        planted))
                .isEmpty();
    }

    /**
     * A self-service, public or token-only controller's handlers are named, and need none. The rule
     * refuses an empty subject, so a compliant protected handler sits beside them.
     */
    @Test
    void the_handlers_of_the_named_unprotected_controllers_are_not_reported() {
        named("com.example.backend.auth.controller.PlantedController")
                .annotatedWith(RestController.class)
                .withMethodAnnotatedWith("read", GetMapping.class, PreAuthorize.class)
                .plantUnder(planted);
        for (String exempt : new String[] {
                "com.example.backend.auth.controller.SelfController",
                "com.example.backend.auth.epic.controller.EpicJwksController",
                "com.example.backend.scim.controller.ScimDiscoveryController"}) {
            named(exempt).annotatedWith(RestController.class)
                    .withMethodAnnotatedWith("read", GetMapping.class)
                    .withMethodAnnotatedWith("write", PostMapping.class)
                    .plantUnder(planted);
        }

        assertThat(violations(ArchitectureTest.every_protected_handler_declares_a_permission,
                        planted))
                .isEmpty();
    }
}
