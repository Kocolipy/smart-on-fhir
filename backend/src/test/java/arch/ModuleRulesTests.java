package arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules in {@link ArchitectureTest} that read a class's module and layer from its package, each
 * shown reporting a planted class and passing one it allows — the service's own classes comply, so
 * the rules alone would never be seen to fail.
 *
 * <p>A package-keyed rule can only be shown failing by a class in the package it reads, and a
 * planted Java class there would sit among the module's real tests. So each planted class is
 * written as bytecode, into a directory of its own, by {@link PlantedClasses#plant}: a class with
 * one field per type it reaches, which is a dependency on that type to ArchUnit whether or not the
 * type exists.
 */
class ModuleRulesTests {

    @TempDir
    Path planted;

    // ---- the module graph ----------------------------------------------------------------------

    @Test
    void a_class_in_an_undeclared_top_level_package_is_reported() {
        assertThat(violations(ArchitectureTest.every_top_level_package_is_a_declared_module,
                        "com.example.backend.undeclared.Planted"))
                .contains("module 'undeclared'");
    }

    @Test
    void an_undeclared_edge_between_modules_is_reported() {
        assertThat(violations(ArchitectureTest.module_dependencies_follow_the_declared_graph,
                        "com.example.backend.patient.application.Planted",
                        "com.example.backend.scim.domain.Reached"))
                .contains("patient -> scim");
    }

    @Test
    void a_declared_edge_between_modules_is_not_reported() {
        assertThat(violations(ArchitectureTest.module_dependencies_follow_the_declared_graph,
                        "com.example.backend.patient.application.Planted",
                        "com.example.backend.observability.Reached"))
                .isEmpty();
    }

    @Test
    void a_shared_module_reaching_a_business_module_is_reported() {
        assertThat(violations(ArchitectureTest.no_shared_depends_on_feature,
                        "com.example.backend.audit.domain.Planted",
                        "com.example.backend.scim.domain.Reached"))
                .contains("business module 'scim'");
    }

    @Test
    void a_shared_module_reaching_another_shared_module_is_not_reported() {
        assertThat(violations(ArchitectureTest.no_shared_depends_on_feature,
                        "com.example.backend.audit.domain.Planted",
                        "com.example.backend.observability.Reached"))
                .isEmpty();
    }

    // ---- the declared edges are used -----------------------------------------------------------

    private static final ArchitectureTest.Edge PATIENT_TO_AUTH =
            new ArchitectureTest.Edge("patient", "auth");

    @Test
    void a_declared_edge_no_class_crosses_is_reported() {
        assertThat(ArchitectureTest.moduleEdgeFindings(
                        imported("com.example.backend.patient.application.Planted"),
                        Map.of("patient", Set.of("auth")), Map.of()))
                .containsExactly("patient -> auth is declared in MODULE_DEPENDENCIES, but no"
                        + " class depends across it");
    }

    @Test
    void an_exempted_edge_a_class_crosses_is_reported() {
        assertThat(ArchitectureTest.moduleEdgeFindings(
                        imported("com.example.backend.patient.application.Planted",
                                "com.example.backend.auth.domain.Reached"),
                        Map.of("patient", Set.of("auth")), Map.of(PATIENT_TO_AUTH, "reserved")))
                .containsExactly("patient -> auth is exempted in DECLARED_UNOBSERVED_EDGES, but"
                        + " a class depends across it");
    }

    @Test
    void an_exempted_edge_that_is_not_declared_is_reported() {
        assertThat(ArchitectureTest.moduleEdgeFindings(
                        imported("com.example.backend.patient.application.Planted"),
                        Map.of("patient", Set.of()), Map.of(PATIENT_TO_AUTH, "reserved")))
                .containsExactly("patient -> auth is exempted in DECLARED_UNOBSERVED_EDGES, but"
                        + " MODULE_DEPENDENCIES does not declare it");
    }

    @Test
    void declared_edges_that_are_crossed_or_exempted_are_not_reported() {
        assertThat(ArchitectureTest.moduleEdgeFindings(
                        imported("com.example.backend.patient.application.Planted",
                                "com.example.backend.observability.Reached"),
                        Map.of("patient", Set.of("auth", "observability")),
                        Map.of(PATIENT_TO_AUTH, "reserved")))
                .isEmpty();
    }

    @Test
    void an_exempted_edge_of_the_service_is_reported_once_a_class_crosses_it() {
        // The service's own exemption list, read by the check the suite runs.
        assertThatThrownBy(() -> ArchitectureTest.every_declared_module_edge_is_observed(
                        imported("com.example.backend.scim.config.Planted",
                                "com.example.backend.profile.Reached")))
                .hasMessageContaining("scim -> profile is exempted in DECLARED_UNOBSERVED_EDGES,"
                        + " but a class depends across it");
    }

    // ---- another module's layers ---------------------------------------------------------------

    @Test
    void reaching_another_modules_adapter_is_reported() {
        assertThat(violations(
                        ArchitectureTest.other_modules_are_reached_through_domain_or_application,
                        "com.example.backend.auth.application.Planted",
                        "com.example.backend.scim.infrastructure.persistence.Reached"))
                .contains("auth reaches scim outside its domain and application layers");
    }

    @Test
    void reaching_another_modules_ports_and_use_cases_is_not_reported() {
        assertThat(violations(
                        ArchitectureTest.other_modules_are_reached_through_domain_or_application,
                        "com.example.backend.auth.application.Planted",
                        "com.example.backend.scim.domain.Reached",
                        "com.example.backend.scim.application.Reached"))
                .isEmpty();
    }

    @Test
    void reaching_a_layerless_shared_module_is_not_reported() {
        assertThat(violations(
                        ArchitectureTest.other_modules_are_reached_through_domain_or_application,
                        "com.example.backend.auth.application.Planted",
                        "com.example.backend.web.Reached"))
                .isEmpty();
    }

    @Test
    void reaching_the_layerless_profile_module_is_not_reported() {
        // Today every reference to LocalProfile is an inlined constant; this one is a field.
        assertThat(violations(
                        ArchitectureTest.other_modules_are_reached_through_domain_or_application,
                        "com.example.backend.scim.application.Planted",
                        "com.example.backend.profile.LocalProfile"))
                .isEmpty();
    }

    @Test
    void the_profile_module_is_shared_and_reaching_it_from_a_shared_module_is_not_reported() {
        assertThat(violations(ArchitectureTest.no_shared_depends_on_feature,
                        "com.example.backend.audit.domain.Planted",
                        "com.example.backend.profile.LocalProfile"))
                .isEmpty();
    }

    // ---- every class of a layered module in a layer --------------------------------------------

    @Test
    void a_class_in_a_layered_modules_root_is_reported() {
        assertThat(violations(ArchitectureTest.every_class_in_a_layered_module_sits_in_a_layer,
                        "com.example.backend.scim.Planted"))
                .contains("com.example.backend.scim.Planted is in no layer of module 'scim'");
    }

    @Test
    void a_class_in_a_package_that_is_no_layer_is_reported() {
        assertThat(violations(ArchitectureTest.every_class_in_a_layered_module_sits_in_a_layer,
                        "com.example.backend.audit.support.Planted"))
                .contains("com.example.backend.audit.support.Planted is in no layer of module"
                        + " 'audit'");
    }

    @Test
    void a_class_below_a_layer_is_not_reported() {
        assertThat(violations(ArchitectureTest.every_class_in_a_layered_module_sits_in_a_layer,
                        "com.example.backend.scim.infrastructure.persistence.Planted"))
                .isEmpty();
    }

    @Test
    void a_class_in_the_epic_root_is_not_reported() {
        assertThat(violations(ArchitectureTest.every_class_in_a_layered_module_sits_in_a_layer,
                        "com.example.backend.auth.epic.Planted"))
                .isEmpty();
    }

    @Test
    void a_class_in_a_layerless_modules_root_is_not_reported() {
        assertThat(violations(ArchitectureTest.every_class_in_a_layered_module_sits_in_a_layer,
                        "com.example.backend.profile.Planted"))
                .isEmpty();
    }

    // ---- the inner layers' framework edges -----------------------------------------------------

    @Test
    void a_use_case_reaching_the_servlet_api_is_reported() {
        assertThat(violations(ArchitectureTest.the_application_layer_knows_no_http,
                        "com.example.backend.scim.application.Planted",
                        "jakarta.servlet.http.HttpServletRequest"))
                .contains("Planted");
    }

    @Test
    void a_use_case_issuing_sql_is_reported() {
        assertThat(violations(ArchitectureTest.inner_layers_issue_no_sql,
                        "com.example.backend.scim.application.Planted",
                        "org.springframework.jdbc.core.JdbcTemplate"))
                .contains("Planted");
    }

    @Test
    void a_domain_class_reaching_the_timed_template_is_reported() {
        assertThat(violations(ArchitectureTest.inner_layers_issue_no_sql,
                        "com.example.backend.scim.domain.Planted",
                        "com.example.backend.database.TimedJdbc"))
                .contains("Planted");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** One planted class, {@code name}, holding a field per type, imported. */
    private JavaClasses imported(String name, String... reaches) {
        return new ClassFileImporter().importPath(PlantedClasses.plant(planted, name, reaches));
    }

    /** {@code rule}'s violations on one planted class, {@code name}, holding a field per type. */
    private String violations(ArchRule rule, String name, String... reaches) {
        EvaluationResult result = rule.evaluate(imported(name, reaches));
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

}
