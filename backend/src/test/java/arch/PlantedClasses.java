package arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Planted classes for the rule tests: bytecode written under a directory as a class file, for
 * rules that read a class's name, package or members, so that a planted class can sit in any
 * package without a source file there.
 *
 * <p>ArchUnit reads class files and never runs the verifier, so a planted class names whatever
 * types it reaches, calls and annotations it carries — a type need not exist for a dependency on
 * it to be read, and one that does is resolved from the classpath.
 */
final class PlantedClasses {

    private PlantedClasses() {
    }

    /**
     * Plants a public final class, {@code name}, holding one private field per type it
     * {@code reaches}, under {@code root}, and returns its class file.
     */
    static Path plant(Path root, String name, String... reaches) {
        return named(name).reaching(reaches).plantUnder(root);
    }

    /** A planted class to shape, named {@code name}: public, final and empty until a step adds. */
    static Planted named(String name) {
        return new Planted(name);
    }

    /** Writes {@code bytes} as {@code name}'s class file under {@code root}, and returns it. */
    static Path write(Path root, String name, byte[] bytes) {
        Path file = root.resolve(name.replace('.', '/') + ".class");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return file;
    }

    /** Every class planted under {@code root}, imported. */
    static JavaClasses imported(Path root) {
        return new ClassFileImporter().importPath(root);
    }

    /** {@code rule}'s violation details on {@code classes}, one a line, or empty when none. */
    static String violations(ArchRule rule, JavaClasses classes) {
        EvaluationResult result = rule.evaluate(classes);
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    /** {@code rule}'s violation details on every class planted under {@code root}. */
    static String violations(ArchRule rule, Path root) {
        return violations(rule, imported(root));
    }

    /**
     * A planted class's shape: its kind, supertypes, annotations and members, each step adding
     * one. Every call, construction and field read sits in a method of its own, which pushes a
     * default for each argument so the bytecode is well formed.
     */
    static final class Planted {

        private final String name;
        private int flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL;
        private String superclass = Object.class.getName();
        private final List<String> interfaces = new ArrayList<>();
        private final List<String> annotations = new ArrayList<>();
        private final List<Consumer<ClassBuilder>> members = new ArrayList<>();
        private int memberCount;

        private Planted(String name) {
            this.name = name;
        }

        /** An interface rather than a class. */
        Planted asInterface() {
            flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT;
            return this;
        }

        Planted extending(String type) {
            superclass = type;
            return this;
        }

        Planted implementing(String... types) {
            interfaces.addAll(List.of(types));
            return this;
        }

        Planted implementing(Class<?>... types) {
            return implementing(namesOf(types));
        }

        /** Runtime-visible annotations on the class, each with no elements. */
        Planted annotatedWith(String... types) {
            annotations.addAll(List.of(types));
            return this;
        }

        /** Runtime-visible annotations on the class, each with no elements. */
        Planted annotatedWith(Class<?>... types) {
            return annotatedWith(namesOf(types));
        }

        /** One private field per type, which ArchUnit reads as a dependency on it. */
        Planted reaching(String... types) {
            for (String type : types) {
                field(type);
            }
            return this;
        }

        /** A private field of {@code type}, carrying {@code annotation}. */
        Planted withFieldAnnotatedWith(String type, String annotation) {
            return field(type, annotation);
        }

        /** A public method, {@code method}, carrying {@code annotations}, that does nothing. */
        Planted withMethodAnnotatedWith(String method, String... annotations) {
            members.add(type -> type.withMethod(method, ConstantDescs.MTD_void,
                    methodFlags(), body -> {
                        body.with(annotationsOf(List.of(annotations)));
                        if (!isInterface()) {
                            body.withCode(CodeBuilder::return_);
                        }
                    }));
            return this;
        }

        /** A public method, {@code method}, carrying {@code annotations}, that does nothing. */
        Planted withMethodAnnotatedWith(String method, Class<?>... annotations) {
            return withMethodAnnotatedWith(method, namesOf(annotations));
        }

        /** A public method, {@code method}, declaring that it throws each of {@code exceptions}. */
        Planted withMethodThrowing(String method, String... exceptions) {
            members.add(type -> type.withMethod(method, ConstantDescs.MTD_void,
                    ClassFile.ACC_PUBLIC, body -> body
                            .with(ExceptionsAttribute.ofSymbols(java.util.Arrays.stream(exceptions)
                                    .map(ClassDesc::of).toArray(ClassDesc[]::new)))
                            .withCode(CodeBuilder::return_)));
            return this;
        }

        /**
         * A call of {@code owner}'s {@code method}, whose descriptor is {@code descriptor}
         * ({@code "()V"}, {@code "(Ljava/time/Clock;)Ljava/time/Instant;"}).
         */
        Planted calling(String owner, String method, String descriptor) {
            MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
            return code(code -> {
                type.parameterList().forEach(parameter -> pushDefault(code, parameter));
                code.invokestatic(ClassDesc.of(owner), method, type);
                popResult(code, type.returnType());
            });
        }

        /** A call of {@code type}'s no-argument constructor. */
        Planted constructing(String type) {
            ClassDesc constructed = ClassDesc.of(type);
            return code(code -> code.new_(constructed).dup()
                    .invokespecial(constructed, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                    .pop());
        }

        /** A read of {@code owner}'s static field {@code field}, of type {@code type}. */
        Planted readingStatic(String owner, String field, String type) {
            return code(code -> code.getstatic(ClassDesc.of(owner), field, ClassDesc.of(type))
                    .pop());
        }

        /** Writes the class under {@code root}, and returns its class file. */
        Path plantUnder(Path root) {
            byte[] bytes = ClassFile.of().build(ClassDesc.of(name), type -> {
                type.withFlags(flags);
                type.withSuperclass(ClassDesc.of(superclass));
                type.withInterfaceSymbols(interfaces.stream().map(ClassDesc::of).toList());
                if (!annotations.isEmpty()) {
                    type.with(annotationsOf(annotations));
                }
                members.forEach(member -> member.accept(type));
            });
            return write(root, name, bytes);
        }

        private Planted field(String type, String... fieldAnnotations) {
            String field = "reached" + memberCount++;
            int fieldFlags = isInterface()
                    ? ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
                    : ClassFile.ACC_PRIVATE;
            members.add(builder -> builder.withField(field, ClassDesc.of(type), declared -> {
                declared.withFlags(fieldFlags);
                if (fieldAnnotations.length > 0) {
                    declared.with(annotationsOf(List.of(fieldAnnotations)));
                }
            }));
            return this;
        }

        private Planted code(Consumer<CodeBuilder> instructions) {
            String method = "planted" + memberCount++;
            members.add(type -> type.withMethodBody(method, ConstantDescs.MTD_void,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        instructions.accept(code);
                        code.return_();
                    }));
            return this;
        }

        private boolean isInterface() {
            return (flags & ClassFile.ACC_INTERFACE) != 0;
        }

        private int methodFlags() {
            return isInterface() ? ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT
                    : ClassFile.ACC_PUBLIC;
        }

        private static String[] namesOf(Class<?>... types) {
            return java.util.Arrays.stream(types).map(Class::getName).toArray(String[]::new);
        }

        private static RuntimeVisibleAnnotationsAttribute annotationsOf(List<String> types) {
            return RuntimeVisibleAnnotationsAttribute.of(types.stream()
                    .map(type -> Annotation.of(ClassDesc.of(type))).toList());
        }

        private static void pushDefault(CodeBuilder code, ClassDesc parameter) {
            switch (TypeKind.from(parameter)) {
                case LONG -> code.lconst_0();
                case FLOAT -> code.fconst_0();
                case DOUBLE -> code.dconst_0();
                case REFERENCE -> code.aconst_null();
                default -> code.iconst_0();
            }
        }

        private static void popResult(CodeBuilder code, ClassDesc result) {
            switch (TypeKind.from(result)) {
                case VOID -> { }
                case LONG, DOUBLE -> code.pop2();
                default -> code.pop();
            }
        }
    }
}
