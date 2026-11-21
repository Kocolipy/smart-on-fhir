package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every architecture rule is shown reporting a planted violation: each {@code @ArchTest} rule and
 * method of {@link ArchitectureTest} and {@link TestSourceArchitectureTest} is read by a
 * {@code *RulesTests} class beside them, which evaluates it on planted classes. A rule whose
 * predicate is wrong, or whose package or name pattern matches nothing, passes on the service's
 * own classes and reads as coverage; the planted test is what shows it can fail, and this sweep
 * holds a new rule to having one.
 *
 * <p>Read from the bytecode: a rule counts as planted when a {@code *RulesTests} class, or a class
 * nested in one, reads its field or calls its method. A mention in a comment does not count.
 */
class PlantedViolationSweepTests {

    /** The classes that declare the architecture rules. */
    private static final List<Class<?>> RULE_CLASSES =
            List.of(ArchitectureTest.class, TestSourceArchitectureTest.class);

    /** A planted-violation test class, or a class nested in one. */
    private static final String RULES_TESTS = "arch\\.\\w+RulesTests(\\$.*)?";

    @Test
    void every_architecture_rule_has_a_planted_violation_test() {
        JavaClasses arch = new ClassFileImporter().importPackages(
                ArchitectureTest.class.getPackageName());

        assertThat(unplanted(arch))
                .as("@ArchTest rules no *RulesTests class evaluates on planted classes")
                .isEmpty();
    }

    @Test
    void a_rule_no_rules_test_reads_is_reported_and_one_that_is_read_is_not() {
        // NamedTargetRulesTests reads one rule, every_name_a_rule_reads_by_string_exists.
        Set<String> unplanted = unplanted(
                new ClassFileImporter().importClasses(NamedTargetRulesTests.class));

        assertThat(unplanted)
                .contains("ArchitectureTest.onion_architecture",
                        "TestSourceArchitectureTest.no_logic_in_test_helpers")
                .doesNotContain("ArchitectureTest.every_name_a_rule_reads_by_string_exists");
    }

    /** {@code Owner.rule} for every {@code @ArchTest} rule no {@code *RulesTests} class reads. */
    static Set<String> unplanted(JavaClasses classes) {
        Set<String> read = classes.stream()
                .filter(type -> type.getName().matches(RULES_TESTS))
                .flatMap(PlantedViolationSweepTests::accessesOf)
                .filter(access -> RULE_CLASSES.stream()
                        .anyMatch(rules -> access.getTargetOwner().isEquivalentTo(rules)))
                .map(access -> access.getTargetOwner().getSimpleName() + "." + access.getName())
                .collect(Collectors.toSet());
        return rules().stream().filter(rule -> !read.contains(rule))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** {@code Owner.rule} for every {@code @ArchTest} field and method of the rule classes. */
    static Set<String> rules() {
        Set<String> rules = new TreeSet<>();
        for (Class<?> owner : RULE_CLASSES) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.isAnnotationPresent(ArchTest.class)) {
                    rules.add(owner.getSimpleName() + "." + field.getName());
                }
            }
            for (Method method : owner.getDeclaredMethods()) {
                if (method.isAnnotationPresent(ArchTest.class)) {
                    rules.add(owner.getSimpleName() + "." + method.getName());
                }
            }
        }
        return rules;
    }

    private static Stream<JavaAccess<?>> accessesOf(JavaClass type) {
        return Stream.concat(type.getFieldAccessesFromSelf().stream(),
                type.getMethodCallsFromSelf().stream());
    }
}
