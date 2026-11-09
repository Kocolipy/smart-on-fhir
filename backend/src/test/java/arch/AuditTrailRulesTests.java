package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.Audited;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ArchitectureTest#the_audit_trail_boundary_admits_no_free_text}, shown reporting a planted
 * audit event that admits a {@code String} and passing the shapes it allows. Every event the
 * service records complies, so the rule alone would never be seen to fail — and the leak it exists
 * to stop is the event someone adds later, which is exactly what a planted one stands for.
 *
 * <p>{@link Audited} is a <strong>sealed</strong> interface, so no test-source class can implement
 * it: the planted events are written as bytecode into a directory of their own, as in
 * {@link UtilityClassRulesTests}, declaring {@code Audited} as an interface. ArchUnit reads class
 * files and never runs the verifier, so the {@code permits} clause does not apply to what it sees —
 * which is the point, because the rule must hold for an event added inside the slice too.
 *
 * <p>Each import names the planted directory and {@code Audited}'s own class file, so
 * {@code areAssignableTo(Audited)} resolves against the real interface rather than a stub.
 */
class AuditTrailRulesTests {

    private static final String EVENT = "com.example.backend.audit.domain.PlantedEvent";

    private static final ClassDesc AUDITED =
            ClassDesc.of("com.example.backend.audit.domain.Audited");

    /** An enum of the audit slice's own vocabulary: a closed set, which the rule admits. */
    private static final ClassDesc A_CLOSED_SET =
            ClassDesc.of("com.example.backend.audit.domain.AuditAdministrativeRefusal");

    @TempDir
    Path planted;

    @Test
    void an_event_holding_a_string_is_reported() {
        plant(event -> event.withField("username", ConstantDescs.CD_String,
                ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));

        assertThat(violations())
                .contains("username")
                .contains("holds a java.lang.String");
    }

    /**
     * The generic case: {@code JavaType#getName()} spells a parameterized field
     * {@code java.util.List<java.lang.String>}, which is why the rule matches the type's whole
     * name rather than comparing it to {@code String}. A collection of usernames is as much a way
     * in as one username.
     */
    @Test
    void an_event_holding_a_list_of_strings_is_reported() {
        plant(event -> event.withField("usernames", ClassDesc.of("java.util.List"), field -> field
                .withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
                .with(SignatureAttribute.of(Signature.parseFrom(
                        "Ljava/util/List<Ljava/lang/String;>;")))));

        assertThat(violations())
                .contains("usernames")
                .contains("holds a java.util.List<java.lang.String>");
    }

    @Test
    void an_event_taking_a_string_in_its_constructor_is_reported() {
        plant(event -> event.withMethodBody(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                ClassFile.ACC_PUBLIC, code -> code.aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                ConstantDescs.MTD_void)
                        .return_()));

        assertThat(violations())
                .contains("<init>")
                .contains("declares a parameter of type java.lang.String");
    }

    @Test
    void an_event_taking_a_string_in_a_method_is_reported() {
        plant(event -> event.withMethodBody("describe",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                ClassFile.ACC_PUBLIC, code -> code.return_()));

        assertThat(violations())
                .contains("describe")
                .contains("declares a parameter of type java.lang.String");
    }

    /**
     * A {@code static} {@code String} is a name the slice spells itself — a resource type, a path
     * name, a reason code — not a value handed in from outside, so the rule skips it. Without that
     * exception every event with a constant would be reported and the rule would be unusable.
     */
    @Test
    void an_event_holding_a_static_string_constant_is_not_reported() {
        plant(event -> event.withField("KIND", ConstantDescs.CD_String,
                ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL));

        assertThat(violations()).isEmpty();
    }

    @Test
    void an_event_holding_only_ids_and_closed_sets_is_not_reported() {
        plant(event -> {
            event.withField("actor", ClassDesc.of("java.util.UUID"),
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            event.withField("subject", ClassDesc.of("java.util.UUID"),
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            event.withField("refusal", A_CLOSED_SET, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
        });

        assertThat(violations()).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Writes {@code EVENT}, an {@link Audited} implementation carrying what {@code members} adds. */
    private void plant(java.util.function.Consumer<java.lang.classfile.ClassBuilder> members) {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(EVENT), event -> {
            event.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            event.withSuperclass(ConstantDescs.CD_Object);
            event.withInterfaceSymbols(AUDITED);
            members.accept(event);
        });
        PlantedClasses.write(planted, EVENT, bytes);
    }

    /** The rule's violation report for the planted event, or empty when it reports none. */
    private String violations() {
        JavaClasses classes = new ClassFileImporter().importPaths(planted, auditedClassFile());
        EvaluationResult result =
                ArchitectureTest.the_audit_trail_boundary_admits_no_free_text.evaluate(classes);
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    /** {@code Audited}'s own class file, so the planted event's interface resolves to it. */
    private static Path auditedClassFile() {
        try {
            return Path.of(Audited.class.getResource("Audited.class").toURI());
        } catch (java.net.URISyntaxException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }
}
