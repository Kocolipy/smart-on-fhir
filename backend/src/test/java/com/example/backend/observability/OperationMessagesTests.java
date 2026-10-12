package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.LogEvent.MessageSlot;
import com.example.backend.observability.LogEvent.Operation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every operation declares the messages its records need. An operation <em>needs</em> a
 * {@link MessageSlot} when production code hands it to the shape that writes that slot's
 * message — {@link LogEvent#success} or {@link LogEvent#successAtWarn} ({@code SUCCESS}),
 * {@link LogEvent#refused}, {@link LogEvent#error}, {@link LogEvent#jobSummary}, and
 * {@link LogEvent#jobScheduled}, which every {@link ScheduledJobSpec#of} reaches.
 *
 * <p>The call sites are read from the compiled production classes. A method needs, for every
 * operation it names — an {@link Operation} constant, or a {@code static final} field holding
 * one, as {@code DormancyService.OPERATION} does — the slot of every shape it calls. Within its
 * own top-level class, nested types included, the reading follows an operation two ways: into
 * a helper that takes an operation (the slots that helper's shapes write are the caller's), and
 * out of a method that returns one (the operations it can return are the caller's). An
 * operation that reaches a shape only through another class's parameter is not seen: the shape
 * still writes its generic message then, rather than failing the path that logs.
 */
class OperationMessagesTests {

    /** The shapes whose message is the operation's own, by the slot each writes. */
    private static final Map<String, MessageSlot> SHAPES = Map.of(
            "success", MessageSlot.SUCCESS,
            "successAtWarn", MessageSlot.SUCCESS,
            "refused", MessageSlot.REFUSED,
            "error", MessageSlot.ERROR,
            "jobScheduled", MessageSlot.SCHEDULED,
            "jobSummary", MessageSlot.SUMMARY);

    /** The operations written only by shapes whose message is the same for every operation. */
    private static final Set<Operation> FIXED_MESSAGE_OPERATIONS =
            EnumSet.of(Operation.EPIC_OUTBOUND, Operation.HTTP_REQUEST);

    private static Map<Operation, Set<MessageSlot>> needs;

    @BeforeAll
    static void readTheCallSites() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.backend");
        needs = new LinkedHashMap<>();
        for (JavaClass type : production) {
            if (topLevel(type).isEquivalentTo(LogEvent.class)) {
                continue;
            }
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                Set<MessageSlot> slots = slotsWritten(unit);
                Set<Operation> operations = operationsNamed(unit);
                for (JavaCodeUnit callee : calleesWithinItsClass(unit)) {
                    if (takesAnOperation(callee)) {
                        slots.addAll(slotsWrittenFor(callee, new HashSet<>()));
                    }
                    if (returnsAnOperation(callee)) {
                        operations.addAll(operationsReturnedBy(callee, new HashSet<>()));
                    }
                }
                for (Operation operation : operations) {
                    needs.computeIfAbsent(operation, o -> EnumSet.noneOf(MessageSlot.class))
                            .addAll(slots);
                }
            }
        }
    }

    /** The reading sees the call sites at all: each kind of route to a shape is found. */
    @Test
    void theCallSitesAreRead() {
        assertThat(needs)
                .as("a literal operation passed straight to the shape")
                .containsKey(Operation.PASSWORD_CHANGE);
        assertThat(needs.get(Operation.PASSWORD_CHANGE))
                .contains(MessageSlot.SUCCESS, MessageSlot.REFUSED);
        assertThat(needs.get(Operation.UNLOCK))
                .as("an operation passed to a helper of the same class")
                .contains(MessageSlot.SUCCESS, MessageSlot.REFUSED);
        assertThat(needs.get(Operation.ACCESS_DENIED))
                .as("an operation a nested type chooses")
                .contains(MessageSlot.REFUSED);
        assertThat(needs.get(Operation.AUDIT_RETENTION))
                .as("an operation held in a static final field, and a job's schedule")
                .contains(MessageSlot.SUMMARY, MessageSlot.SCHEDULED);
    }

    /**
     * The completeness check: no operation is handed to a shape whose message it does not
     * declare, so no record of this service carries a shape's generic message.
     */
    @Test
    void everyOperationDeclaresEveryMessageItsCallSitesNeed() {
        Set<String> missing = new TreeSet<>();
        needs.forEach((operation, slots) -> slots.stream()
                .filter(slot -> !operation.declares(slot))
                .forEach(slot -> missing.add(operation + " needs a " + slot + " message")));

        assertThat(missing).isEmpty();
    }

    /**
     * An operation that declares no message at all is one only the fixed-message shapes write,
     * so an operation added with none — and handed to a shape some way the reading cannot
     * see — is still a failure here.
     */
    @Test
    void anOperationWithNoMessageIsOneOnlyAFixedMessageShapeWrites() {
        assertThat(Arrays.stream(Operation.values())
                        .filter(operation -> Arrays.stream(MessageSlot.values())
                                .noneMatch(operation::declares)))
                .containsExactlyInAnyOrderElementsOf(FIXED_MESSAGE_OPERATIONS);
    }

    /**
     * The other direction: a declared message some call site needs, so the table cannot keep a
     * message after the last record that wrote it is gone. {@code SUCCESS_ON_CHANGE} is chosen
     * by a record's type, not by the shape, so the reading cannot see it;
     * {@code LogEventTests.theRoleMappingChangeRecordNamesTheSessionsItEnded} pins the one there is.
     */
    @Test
    void everyDeclaredMessageIsOneACallSiteNeeds() {
        Set<String> unneeded = new TreeSet<>();
        for (Operation operation : Operation.values()) {
            for (MessageSlot slot : MessageSlot.values()) {
                if (slot != MessageSlot.SUCCESS_ON_CHANGE && operation.declares(slot)
                        && !needs.getOrDefault(operation, Set.of()).contains(slot)) {
                    unneeded.add(operation + " declares a " + slot + " message no call site needs");
                }
            }
        }

        assertThat(unneeded).isEmpty();
    }

    /**
     * The slots a helper taking an operation writes for the operation it is given: the shapes it
     * calls, and those of the helpers taking an operation it calls in turn.
     */
    private static Set<MessageSlot> slotsWrittenFor(JavaCodeUnit helper, Set<JavaCodeUnit> seen) {
        Set<MessageSlot> slots = slotsWritten(helper);
        if (seen.add(helper)) {
            calleesWithinItsClass(helper).stream()
                    .filter(OperationMessagesTests::takesAnOperation)
                    .forEach(callee -> slots.addAll(slotsWrittenFor(callee, seen)));
        }
        return slots;
    }

    /** The operations a method returning one can return, through the methods it calls in turn. */
    private static Set<Operation> operationsReturnedBy(JavaCodeUnit method, Set<JavaCodeUnit> seen) {
        Set<Operation> operations = operationsNamed(method);
        if (seen.add(method)) {
            calleesWithinItsClass(method).stream()
                    .filter(OperationMessagesTests::returnsAnOperation)
                    .forEach(callee -> operations.addAll(operationsReturnedBy(callee, seen)));
        }
        return operations;
    }

    /** The code units of its own top-level class — nested types included — that {@code unit} calls. */
    private static Set<JavaCodeUnit> calleesWithinItsClass(JavaCodeUnit unit) {
        JavaClass home = topLevel(unit.getOwner());
        Set<JavaCodeUnit> callees = new HashSet<>();
        unit.getCallsFromSelf().stream()
                .map(call -> call.getTarget().resolveMember())
                .flatMap(Optional::stream)
                .filter(callee -> topLevel(callee.getOwner()).equals(home))
                .forEach(callees::add);
        return callees;
    }

    private static boolean takesAnOperation(JavaCodeUnit unit) {
        return unit.getRawParameterTypes().stream()
                .anyMatch(parameter -> parameter.isEquivalentTo(Operation.class));
    }

    private static boolean returnsAnOperation(JavaCodeUnit unit) {
        return unit.getRawReturnType().isEquivalentTo(Operation.class);
    }

    private static Set<MessageSlot> slotsWritten(JavaCodeUnit unit) {
        Set<MessageSlot> slots = EnumSet.noneOf(MessageSlot.class);
        unit.getMethodCallsFromSelf().forEach(call -> {
            JavaClass owner = call.getTarget().getOwner();
            String name = call.getTarget().getName();
            if (owner.isEquivalentTo(LogEvent.class) && SHAPES.containsKey(name)) {
                slots.add(SHAPES.get(name));
            } else if (owner.isEquivalentTo(ScheduledJobSpec.class) && name.equals("of")) {
                slots.add(MessageSlot.SCHEDULED);
            }
        });
        return slots;
    }

    private static Set<Operation> operationsNamed(JavaCodeUnit unit) {
        Set<Operation> operations = EnumSet.noneOf(Operation.class);
        for (JavaFieldAccess access : unit.getFieldAccesses()) {
            if (access.getAccessType() != JavaFieldAccess.AccessType.GET) {
                continue;
            }
            access.getTarget().resolveMember()
                    .filter(field -> field.getRawType().isEquivalentTo(Operation.class))
                    .filter(field -> field.getModifiers()
                            .containsAll(Set.of(JavaModifier.STATIC, JavaModifier.FINAL)))
                    .map(OperationMessagesTests::valueOf)
                    .ifPresent(operations::add);
        }
        return operations;
    }

    /** The operation a {@code static final} field holds: an enum constant, or a field set to one. */
    private static Operation valueOf(JavaField field) {
        try {
            Field reflected = field.getOwner().reflect().getDeclaredField(field.getName());
            reflected.setAccessible(true);
            return (Operation) reflected.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + field.getFullName(), e);
        }
    }

    private static JavaClass topLevel(JavaClass type) {
        JavaClass outer = type;
        while (outer.getEnclosingClass().isPresent()) {
            outer = outer.getEnclosingClass().get();
        }
        return outer;
    }
}
