package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TestSourceArchitectureTest#no_logic_in_test_helpers}, shown reporting a planted helper of
 * each name it holds — the suite's own helpers comply, so the rule alone would never be seen to
 * fail. Each planted class is bytecode with one field per type it reaches, written by
 * {@link PlantedClasses#plant}, as in {@link ModuleRulesTests}.
 */
class TestHelperRulesTests {

    private static final String ASSERTIONS = "org.assertj.core.api.Assertions";

    @TempDir
    Path planted;

    @Test
    void a_fixtures_class_asserting_is_reported() {
        assertThat(violations("com.example.backend.scim.PlantedFixtures", ASSERTIONS))
                .contains("PlantedFixtures");
    }

    @Test
    void a_fixture_class_asserting_is_reported() {
        assertThat(violations("com.example.backend.database.config.PlantedFixture", ASSERTIONS))
                .contains("PlantedFixture");
    }

    @Test
    void a_fake_asserting_is_reported() {
        assertThat(violations("com.example.backend.auth.epic.FakePlanted",
                        "org.junit.jupiter.api.Assertions"))
                .contains("FakePlanted");
    }

    @Test
    void a_class_nested_in_a_fake_asserting_is_reported() {
        assertThat(violations("com.example.backend.auth.epic.FakePlanted$Handler", ASSERTIONS))
                .contains("FakePlanted$Handler");
    }

    @Test
    void a_helper_that_only_builds_is_not_reported() {
        assertThat(violations("com.example.backend.auth.epic.FakePlanted", "java.util.List"))
                .isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The rule's violations on one planted class, {@code name}, holding a field per type. */
    private String violations(String name, String... reaches) {
        EvaluationResult result = TestSourceArchitectureTest.no_logic_in_test_helpers
                .evaluate(new ClassFileImporter().importPath(PlantedClasses.plant(planted, name, reaches)));
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

}
