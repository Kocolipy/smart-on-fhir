package arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

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

    /**
     * Every annotation that stops a test from running, or runs it only sometimes: {@code @Disabled}
     * and the {@code @DisabledIf…} family, and the whole of {@code org.junit.jupiter.api.condition}
     * — {@code @EnabledIfEnvironmentVariable} and {@code @EnabledOnOs} as much as their disabling
     * twins, because a test that runs only where a variable or an OS happens to match is a test
     * nobody can tell has stopped running.
     */
    private static final DescribedPredicate<JavaAnnotation<?>> SKIPS_A_TEST =
        DescribedPredicate.describe("a JUnit disabling or conditional annotation",
                annotation -> annotation.getRawType().getName().matches(
                        "org\\.junit\\.jupiter\\.api\\.Disabled.*"
                                + "|org\\.junit\\.jupiter\\.api\\.condition\\..*"));

    /**
     * No test is disabled, and none runs only under a condition.
     *
     * <p>AGENTS.md ("Test suite policy"): a broken test is corrected from source authority or
     * removed with {@code git rm}; {@code @Disabled} and skip markers stay out of the tree. Nothing
     * enforced that before — a disabled test is indistinguishable, in a green gate's summary, from
     * one that passed, so the behaviour it names is uncovered and nothing says so.
     *
     * <p>Two rules, because a marker sits on either a class or a method, and ArchUnit's subjects
     * are separate. Each asks for a <em>meta</em>-annotation, which in ArchUnit includes a direct
     * one, so one condition covers both {@code @Disabled} itself and a custom annotation that
     * carries it. Neither allows an empty subject — every test source has classes and methods, so
     * an empty one would mean the import broke.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_test_class_is_disabled_or_conditional =
        noClasses()
            .should().beMetaAnnotatedWith(SKIPS_A_TEST)
            .because("a disabled or conditional test class reports success for behaviour nothing"
                    + " ran; AGENTS.md: correct it from source authority or git rm it");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_test_method_is_disabled_or_conditional =
        noMethods()
            .should().beMetaAnnotatedWith(SKIPS_A_TEST)
            .because("a disabled or conditional test reports success for behaviour nothing ran;"
                    + " AGENTS.md: correct it from source authority or git rm it");

    /**
     * No test abandons itself part-way through.
     *
     * <p>An assumption is the same hole as {@code @Disabled} with no annotation to grep for: a
     * failed {@code assumeTrue} aborts the test and the run still reports it as not-failed, so a
     * test guarded by one can stop exercising anything and no summary changes. Both libraries in
     * use here offer them ({@code org.junit.jupiter.api.Assumptions},
     * {@code org.assertj.core.api.Assumptions}), so both are named. A fact a test needs is either
     * arranged by the test or asserted by it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_test_abandons_itself_on_an_assumption =
        noClasses()
            .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.junit.jupiter.api.Assumptions")
            .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.assertj.core.api.Assumptions")
            .because("an aborted test is reported as not-failed, so an assumption hides an"
                    + " uncovered behaviour exactly as @Disabled does");
}
