package arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.BackendApplication;
import com.example.backend.auth.epic.InvalidEpicConfigurationException;
import com.example.backend.authorization.domain.InvalidGroupPermissionsException;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.infrastructure.transaction.DeadlockRetryAdapter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ArchitectureTest#utility_classes_private_constructor}, shown reporting a planted
 * static-only class that can be instantiated or extended, and passing what it allows or does not
 * select. The service's own utility classes comply, so the rule alone would never be seen to fail.
 *
 * <p>The rule reads a class's shape — top-level, its constructors, its static and instance members,
 * whether it is final — so each planted class is written as bytecode with exactly the members a case
 * needs, into a directory of its own.
 */
class UtilityClassRulesTests {

    @TempDir
    Path planted;

    @Test
    void a_static_only_class_that_is_not_final_is_reported() {
        plant(new Shape("com.example.backend.scim.domain.PlantedHelpers")
                .notFinal().privateConstructor().staticMethod());

        assertThat(violations()).contains("PlantedHelpers");
    }

    @Test
    void a_static_only_class_with_a_public_constructor_is_reported() {
        plant(new Shape("com.example.backend.scim.domain.PlantedHelpers")
                .publicConstructor().staticMethod());

        assertThat(violations()).contains("PlantedHelpers");
    }

    @Test
    void a_static_only_class_holding_only_a_constant_is_reported() {
        plant(new Shape("com.example.backend.profile.PlantedProfile")
                .publicConstructor().staticField());

        assertThat(violations()).contains("PlantedProfile");
    }

    @Test
    void a_final_static_only_class_with_only_private_constructors_is_not_reported() {
        plant(new Shape("com.example.backend.scim.domain.PlantedHelpers")
                .privateConstructor().staticMethod());

        assertThat(violations()).isEmpty();
    }

    @Test
    void a_class_with_an_instance_method_is_not_held_to_it() {
        plant(new Shape("com.example.backend.scim.domain.PlantedHelpers")
                .privateConstructor().staticMethod());
        plant(new Shape("com.example.backend.scim.domain.PlantedService")
                .notFinal().publicConstructor().staticMethod().instanceMethod());

        assertThat(violations()).isEmpty();
    }

    @Test
    void a_class_whose_only_static_member_is_synthetic_is_not_held_to_it() {
        // A lambda in a constructor compiles to a private static synthetic method.
        plant(new Shape("com.example.backend.scim.domain.PlantedHelpers")
                .privateConstructor().staticMethod());
        plant(new Shape("com.example.backend.scim.domain.PlantedComponent")
                .notFinal().publicConstructor().syntheticStaticMethod());

        assertThat(violations()).isEmpty();
    }

    @Test
    void the_shapes_false_positives_are_not_held_to_it() {
        // Each of these is non-final with a public constructor, so selecting one would report it.
        JavaClasses classes = new ClassFileImporter().importClasses(
                InvalidEpicConfigurationException.class, InvalidGroupPermissionsException.class,
                DeadlockRetryAdapter.class, BackendApplication.class, LogEvent.class);

        assertThat(violations(classes)).isEmpty();
    }

    /** Without a static-only class the rule checks nothing, and fails rather than pass. */
    @Test
    void the_rule_fails_on_a_class_set_with_no_static_only_class() {
        plant(new Shape("com.example.backend.scim.domain.PlantedService")
                .publicConstructor().instanceMethod());
        JavaClasses classes = new ClassFileImporter().importPath(planted);

        assertThatThrownBy(() -> ArchitectureTest.utility_classes_private_constructor
                        .evaluate(classes))
                .hasMessageContaining("failed to check any classes");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String violations() {
        return violations(new ClassFileImporter().importPath(planted));
    }

    private static String violations(JavaClasses classes) {
        EvaluationResult result = ArchitectureTest.utility_classes_private_constructor
                .evaluate(classes);
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    /** A planted class's flags and members; final, with no members, unless a step says otherwise. */
    private record Shape(String name, boolean isFinal, int constructor, boolean hasStaticMethod,
            boolean hasStaticField, boolean hasInstanceMethod, boolean hasSyntheticStaticMethod) {

        private static final int NO_CONSTRUCTOR = -1;

        Shape(String name) {
            this(name, true, NO_CONSTRUCTOR, false, false, false, false);
        }

        Shape notFinal() {
            return new Shape(name, false, constructor, hasStaticMethod, hasStaticField,
                    hasInstanceMethod, hasSyntheticStaticMethod);
        }

        Shape privateConstructor() {
            return new Shape(name, isFinal, ClassFile.ACC_PRIVATE, hasStaticMethod, hasStaticField,
                    hasInstanceMethod, hasSyntheticStaticMethod);
        }

        Shape publicConstructor() {
            return new Shape(name, isFinal, ClassFile.ACC_PUBLIC, hasStaticMethod, hasStaticField,
                    hasInstanceMethod, hasSyntheticStaticMethod);
        }

        Shape staticMethod() {
            return new Shape(name, isFinal, constructor, true, hasStaticField, hasInstanceMethod,
                    hasSyntheticStaticMethod);
        }

        Shape staticField() {
            return new Shape(name, isFinal, constructor, hasStaticMethod, true, hasInstanceMethod,
                    hasSyntheticStaticMethod);
        }

        Shape instanceMethod() {
            return new Shape(name, isFinal, constructor, hasStaticMethod, hasStaticField, true,
                    hasSyntheticStaticMethod);
        }

        Shape syntheticStaticMethod() {
            return new Shape(name, isFinal, constructor, hasStaticMethod, hasStaticField,
                    hasInstanceMethod, true);
        }
    }

    private void plant(Shape shape) {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(shape.name()), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | (shape.isFinal() ? ClassFile.ACC_FINAL : 0));
            type.withSuperclass(ConstantDescs.CD_Object);
            if (shape.constructor() != Shape.NO_CONSTRUCTOR) {
                type.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void,
                        shape.constructor(), code -> code.aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                        ConstantDescs.MTD_void)
                                .return_());
            }
            if (shape.hasStaticMethod()) {
                type.withMethodBody("help", ConstantDescs.MTD_void,
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_());
            }
            if (shape.hasStaticField()) {
                type.withField("NAME", ConstantDescs.CD_String,
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            }
            if (shape.hasInstanceMethod()) {
                type.withMethodBody("serve", ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        code -> code.return_());
            }
            if (shape.hasSyntheticStaticMethod()) {
                type.withMethodBody("lambda$new$0", ConstantDescs.MTD_void,
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        code -> code.return_());
            }
        });
        PlantedClasses.write(planted, shape.name(), bytes);
    }
}
