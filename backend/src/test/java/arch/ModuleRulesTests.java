package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules in {@link ArchitectureTest} that read a class's module and layer from its package, each
 * shown reporting a planted class and passing one it allows — the service's own classes comply, so
 * the rules alone would never be seen to fail.
 *
 * <p>A package-keyed rule can only be shown failing by a class in the package it reads, and a
 * planted Java class there would sit among the module's real tests. So each planted class is
 * written as bytecode, into a directory of its own: a class with one field per type it reaches,
 * which is a dependency on that type to ArchUnit whether or not the type exists.
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
                        "com.example.backend.auth.domain.Reached"))
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

    /** {@code rule}'s violations on one planted class, {@code name}, holding a field per type. */
    private String violations(ArchRule rule, String name, String... reaches) {
        EvaluationResult result =
                rule.evaluate(new ClassFileImporter().importPath(plant(name, reaches)));
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    private Path plant(String name, String... reaches) {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(name), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            type.withSuperclass(ConstantDescs.CD_Object);
            for (int field = 0; field < reaches.length; field++) {
                type.withField("reached" + field, ClassDesc.of(reaches[field]),
                        ClassFile.ACC_PRIVATE);
            }
        });
        Path file = planted.resolve(name.replace('.', '/') + ".class");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return file;
    }
}
