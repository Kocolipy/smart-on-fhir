package arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rules over the test sources.
 *
 * <p>{@link ArchitectureTest} imports production classes only, so a rule about test code needs its
 * own import. Kept in a class of its own rather than as a second importer inside that one, so each
 * class has exactly one class set and every rule in it reads against the same thing.
 */
@com.tngtech.archunit.junit.AnalyzeClasses(
    packages = "com.example.backend",
    importOptions = {ImportOption.OnlyIncludeTests.class}
)
public class TestSourceArchitectureTest {

    /**
     * The test helpers, by name: a {@code *Fixture} or {@code *Fixtures} class ({@code DevFixtures},
     * {@code DatabaseConnectionsFixture}) or a {@code Fake*} stand-in ({@code FakeEpic}), top-level
     * or nested, with the classes nested in one.
     */
    private static final String TEST_HELPERS = ".*[.$](\\w*Fixtures?|Fake\\w*)(\\$.*)?";

    // Never allowed an empty subject: the helpers it names exist, and a rename that left it
    // matching none would otherwise hold nothing.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_logic_in_test_helpers =
        noClasses()
            .that().haveNameMatching(TEST_HELPERS)
            .should().dependOnClassesThat().resideInAnyPackage("org.assertj..", "org.junit.jupiter.api..")
            .because("A fixture or a fake builds inputs; an assertion inside one fails in a helper"
                    + " instead of in the test that owns the expectation");
}
