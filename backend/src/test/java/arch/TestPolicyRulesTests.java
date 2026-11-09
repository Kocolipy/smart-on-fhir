package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test-suite policy rules in {@link TestSourceArchitectureTest}, each shown reporting the
 * marker it forbids and passing an ordinary test. The tree carries none of these markers — which
 * is the policy working, and also why the rules alone would never be seen to fail.
 *
 * <p>Every planted class is written as bytecode into a directory of its own, as in
 * {@link UtilityClassRulesTests}, rather than as a nested class here. A nested
 * {@code @Disabled} class would compile into {@code target/test-classes}, where
 * {@link TestSourceArchitectureTest}'s own import would find it and report it — and the rule would
 * then need a carve-out naming this file, which is a hole in the rule itself. Planting keeps the
 * rule free of one.
 *
 * <p>JUnit's and AssertJ's own annotation and {@code Assumptions} types are named as strings for
 * the same reason: importing them here would make this class a real violation of the rules it
 * tests.
 */
class TestPolicyRulesTests {

    private static final String PLANTED = "com.example.backend.planted.PlantedTests";

    private static final ClassDesc TEST = ClassDesc.of("org.junit.jupiter.api.Test");
    private static final ClassDesc DISABLED = ClassDesc.of("org.junit.jupiter.api.Disabled");
    private static final ClassDesc DISABLED_IF_ENVIRONMENT_VARIABLE = ClassDesc.of(
            "org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable");
    private static final ClassDesc JUNIT_ASSUMPTIONS =
            ClassDesc.of("org.junit.jupiter.api.Assumptions");

    @TempDir
    Path planted;

    @Test
    void a_disabled_test_method_is_reported() {
        plant(onTheClass -> {
        }, method -> method.with(RuntimeVisibleAnnotationsAttribute.of(
                Annotation.of(TEST), Annotation.of(DISABLED))));

        assertThat(violations(TestSourceArchitectureTest.no_test_method_is_disabled_or_conditional))
                .contains("Method <com.example.backend.planted.PlantedTests.aTest()>")
                .contains("is meta-annotated with a JUnit disabling or conditional annotation");
    }

    /**
     * A conditional annotation on the class, which disables every test in it at once. Its
     * {@code named} and {@code matches} elements are required, so the planted annotation carries
     * them: an annotation missing a mandatory element is still readable by ArchUnit, but one
     * written as the real thing proves the rule matches what a developer would actually write.
     */
    @Test
    void a_conditionally_disabled_test_class_is_reported() {
        plant(onTheClass -> onTheClass.with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(DISABLED_IF_ENVIRONMENT_VARIABLE,
                                AnnotationElement.of("named", AnnotationValue.ofString("CI")),
                                AnnotationElement.of("matches", AnnotationValue.ofString("true"))))),
                method -> method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(TEST))));

        assertThat(violations(TestSourceArchitectureTest.no_test_class_is_disabled_or_conditional))
                .contains("Class <com.example.backend.planted.PlantedTests>")
                .contains("is meta-annotated with a JUnit disabling or conditional annotation");
    }

    @Test
    void a_test_that_abandons_itself_on_an_assumption_is_reported() {
        plant(onTheClass -> {
        }, method -> method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(TEST))),
                code -> code.iconst_1()
                        .invokestatic(JUNIT_ASSUMPTIONS, "assumeTrue",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean))
                        .return_());

        assertThat(violations(TestSourceArchitectureTest.no_test_abandons_itself_on_an_assumption))
                .contains("PlantedTests")
                .contains("Assumptions");
    }

    @Test
    void an_ordinary_test_is_reported_by_none_of_them() {
        plant(onTheClass -> {
        }, method -> method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(TEST))));

        assertThat(violations(TestSourceArchitectureTest.no_test_class_is_disabled_or_conditional))
                .isEmpty();
        assertThat(violations(TestSourceArchitectureTest.no_test_method_is_disabled_or_conditional))
                .isEmpty();
        assertThat(violations(TestSourceArchitectureTest.no_test_abandons_itself_on_an_assumption))
                .isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void plant(java.util.function.Consumer<java.lang.classfile.ClassBuilder> onTheClass,
            java.util.function.Consumer<java.lang.classfile.MethodBuilder> onTheMethod) {
        plant(onTheClass, onTheMethod, code -> code.return_());
    }

    /** Writes {@code PLANTED}, one test class with one method, {@code aTest}. */
    private void plant(java.util.function.Consumer<java.lang.classfile.ClassBuilder> onTheClass,
            java.util.function.Consumer<java.lang.classfile.MethodBuilder> onTheMethod,
            java.util.function.Consumer<java.lang.classfile.CodeBuilder> body) {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(PLANTED), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC);
            type.withSuperclass(ConstantDescs.CD_Object);
            onTheClass.accept(type);
            type.withMethod("aTest", ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, method -> {
                method.withCode(body);
                onTheMethod.accept(method);
            });
        });
        PlantedClasses.write(planted, PLANTED, bytes);
    }

    /** The rule's violation report for the planted class, or empty when it reports none. */
    private String violations(ArchRule rule) {
        EvaluationResult result = rule.evaluate(new ClassFileImporter().importPath(planted));
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }
}
