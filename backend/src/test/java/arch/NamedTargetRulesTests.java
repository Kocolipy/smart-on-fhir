package arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * {@link ArchitectureTest#every_name_a_rule_reads_by_string_exists}, shown failing on a class set
 * that lacks what the rules name — the service's own classes have all of it, so the check alone
 * would never be seen to fail.
 */
class NamedTargetRulesTests {

    @Test
    void a_method_a_rule_names_that_no_class_has_is_reported() {
        assertThatThrownBy(() -> ArchitectureTest.every_name_a_rule_reads_by_string_exists(
                        new ClassFileImporter().importClasses(NamedTargetRulesTests.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ScimResourceJpaRepository#lockResource")
                .hasMessageContaining("ScimLoginStateRepository#imposeLock")
                .hasMessageContaining("ScimAttribute#singular");
    }

    @Test
    void a_type_a_rule_names_that_no_class_has_is_reported() {
        assertThatThrownBy(() -> ArchitectureTest.every_name_a_rule_reads_by_string_exists(
                        new ClassFileImporter().importClasses(NamedTargetRulesTests.class)))
                .hasMessageContaining("com.example.backend.auth.controller.SessionEstablishment");
    }
}
