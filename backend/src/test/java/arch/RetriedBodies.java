package arch;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * What a retried write's body does after a fail-open audit event, read from its bytecode: the
 * analysis behind {@code ArchitectureTest.a_retried_write_ends_at_its_fail_open_audit_event}.
 *
 * <p>A fail-open event ({@link Audited.Refused}) is appended in a transaction of its own and
 * commits at once (ADR 0004). If anything the body does after it can deadlock, the retry runs the
 * body again and appends the event a second time — a duplicate row nothing removes. So the event
 * ends its path: from the instruction that constructs it, no path may reach a collaborator — a
 * call on a port (an interface of this service's, the audit trail excepted) or on another bean —
 * before the path throws or returns. ArchUnit reads calls and their lines but not the order of
 * paths through a method, which is the whole question here, so the paths are read from the
 * method's code with the JDK's class-file API.
 *
 * <p>Calls are followed into the body's own class and into the beans it calls, so an event
 * appended by a helper or a collaborator that then returns counts at the call: the caller must
 * end there too. Not followed: a call through a port (an implementation is not known statically;
 * the call itself is a collaborator), an invoked lambda (after-commit work, which a retry never
 * repeats), and an exception handler's path. {@link Audited.Committed} events are not counted:
 * they are appended after the commit, which no retry follows.
 */
final class RetriedBodies {

    private final Map<String, Summary> summaries = new HashMap<>();

    private final List<String> violations = new ArrayList<>();

    private RetriedBodies() {
    }

    /**
     * Every place the body of {@code owner.name(descriptor)}, or a method it calls, reaches a
     * collaborator after a fail-open event; empty when it ends at each one.
     */
    static List<String> collaboratorsAfterAFailOpenEvent(
            String owner, String name, String descriptor) {
        RetriedBodies bodies = new RetriedBodies();
        bodies.summary(owner, name, descriptor);
        return List.copyOf(bodies.violations);
    }

    /**
     * What a method's callers need to know of it: whether it may return after an event, and
     * whether it reaches a collaborator at all.
     */
    private record Summary(boolean returnsAfterAnEvent, boolean reachesACollaborator) {

        static final Summary NOTHING = new Summary(false, false);
    }

    private Summary summary(String owner, String name, String descriptor) {
        String key = owner + "." + name + descriptor;
        Summary known = summaries.get(key);
        if (known != null) {
            return known;
        }
        // Assumed for a method being read already, so a recursive call ends the descent.
        summaries.put(key, Summary.NOTHING);
        Summary read = method(owner, name, descriptor)
                .map(code -> read(owner, name, code))
                .orElse(Summary.NOTHING);
        summaries.put(key, read);
        return read;
    }

    private Summary read(String owner, String name, CodeModel code) {
        List<CodeElement> elements = code.elementList();
        Map<Label, Integer> labels = new HashMap<>();
        int[] lines = new int[elements.size()];
        int line = 0;
        for (int i = 0; i < elements.size(); i++) {
            CodeElement element = elements.get(i);
            if (element instanceof LabelTarget target) {
                labels.put(target.label(), i);
            } else if (element instanceof LineNumber number) {
                line = number.line();
            }
            lines[i] = line;
        }
        boolean returnsAfterAnEvent = false;
        boolean reachesACollaborator = false;
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) instanceof InvokeInstruction invoke) {
                reachesACollaborator |= reaches(owner, invoke);
                if (isEvent(owner, invoke)) {
                    returnsAfterAnEvent |= follow(owner, name, elements, labels, lines, i);
                }
            }
        }
        return new Summary(returnsAfterAnEvent, reachesACollaborator);
    }

    /**
     * Walks every path on from the event at {@code event}, recording each collaborator it reaches;
     * answers whether one of them returns.
     */
    private boolean follow(String owner, String name, List<CodeElement> elements,
            Map<Label, Integer> labels, int[] lines, int event) {
        boolean returns = false;
        BitSet seen = new BitSet();
        Deque<Integer> next = new ArrayDeque<>(successors(elements, labels, event));
        while (!next.isEmpty()) {
            int at = next.pop();
            if (at >= elements.size() || seen.get(at)) {
                continue;
            }
            seen.set(at);
            CodeElement element = elements.get(at);
            if (element instanceof InvokeInstruction invoke && reaches(owner, invoke)) {
                violations.add(simpleName(owner) + "." + name + " (line " + lines[at] + ") calls "
                        + simpleName(invoke.owner().asInternalName()) + "." + invoke.name()
                                .stringValue()
                        + " after the fail-open audit event at line " + lines[event]);
                continue;
            }
            if (element instanceof ReturnInstruction) {
                returns = true;
                continue;
            }
            next.addAll(successors(elements, labels, at));
        }
        return returns;
    }

    /**
     * Whether {@code invoke} appends a fail-open event: it constructs a {@link Audited.Refused},
     * or calls a method of this class or of a bean that may return after one.
     */
    private boolean isEvent(String owner, InvokeInstruction invoke) {
        String target = invoke.owner().asInternalName();
        if (invoke.opcode() == Opcode.INVOKESPECIAL && invoke.name().equalsString("<init>")) {
            return type(target).filter(Audited.Refused.class::isAssignableFrom).isPresent();
        }
        return followed(owner, target)
                && summary(target, invoke.name().stringValue(), invoke.type().stringValue())
                        .returnsAfterAnEvent();
    }

    /** Whether {@code invoke} is a collaborator, or a method of this class that calls one. */
    private boolean reaches(String owner, InvokeInstruction invoke) {
        String target = invoke.owner().asInternalName();
        return isCollaborator(owner, invoke) || target.equals(owner)
                && summary(target, invoke.name().stringValue(), invoke.type().stringValue())
                        .reachesACollaborator();
    }

    /** A port of this service's other than the audit trail, or a bean other than this class. */
    private static boolean isCollaborator(String owner, InvokeInstruction invoke) {
        if (invoke.opcode() == Opcode.INVOKESTATIC || invoke.name().equalsString("<init>")) {
            return false;
        }
        String target = invoke.owner().asInternalName();
        return !target.equals(owner) && type(target)
                .filter(type -> type.getName().startsWith("com.example.backend.")
                        && type != AuditTrail.class
                        && (type.isInterface() && !Audited.class.isAssignableFrom(type)
                                || isBean(type)))
                .isPresent();
    }

    /** Calls whose bodies are read: into this class, and into another bean. */
    private static boolean followed(String owner, String target) {
        return target.equals(owner) || type(target).filter(RetriedBodies::isBean).isPresent();
    }

    private static boolean isBean(Class<?> type) {
        return !type.isInterface() && AnnotatedElementUtils.hasAnnotation(type, Component.class);
    }

    private static List<Integer> successors(
            List<CodeElement> elements, Map<Label, Integer> labels, int at) {
        CodeElement element = elements.get(at);
        if (element instanceof ReturnInstruction || element instanceof ThrowInstruction) {
            return List.of();
        }
        if (element instanceof BranchInstruction branch) {
            int target = labels.get(branch.target());
            return branch.opcode() == Opcode.GOTO || branch.opcode() == Opcode.GOTO_W
                    ? List.of(target)
                    : List.of(target, at + 1);
        }
        if (element instanceof TableSwitchInstruction table) {
            return switchTargets(labels, table.defaultTarget(), table.cases());
        }
        if (element instanceof LookupSwitchInstruction lookup) {
            return switchTargets(labels, lookup.defaultTarget(), lookup.cases());
        }
        return List.of(at + 1);
    }

    private static List<Integer> switchTargets(
            Map<Label, Integer> labels, Label fallback, List<SwitchCase> cases) {
        List<Integer> targets = new ArrayList<>();
        targets.add(labels.get(fallback));
        for (SwitchCase option : cases) {
            targets.add(labels.get(option.target()));
        }
        return targets;
    }

    private static Optional<CodeModel> method(String owner, String name, String descriptor) {
        ClassModel model = model(owner);
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString(name)
                    && method.methodType().equalsString(descriptor)) {
                return method.code();
            }
        }
        // Declared by a superclass: read it there.
        return model.superclass()
                .map(parent -> parent.asInternalName())
                .filter(parent -> !parent.startsWith("java/"))
                .flatMap(parent -> method(parent, name, descriptor));
    }

    private static ClassModel model(String internalName) {
        String resource = internalName + ".class";
        try (InputStream bytes = RetriedBodies.class.getClassLoader().getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("no class file for " + internalName);
            }
            return ClassFile.of().parse(bytes.readAllBytes());
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Optional<Class<?>> type(String internalName) {
        try {
            // The name is a JVM internal name read from this build's own compiled classes (an
            // invoke instruction's owner), never external input; the class is loaded uninitialized.
            // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
            return Optional.of(Class.forName(internalName.replace('/', '.'), false,
                    RetriedBodies.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError absent) {
            return Optional.empty();
        }
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
