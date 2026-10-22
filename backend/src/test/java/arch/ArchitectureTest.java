package arch;

import com.example.backend.scim.config.ScimSecurityConfig;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.ErrorDocumentFilter;
import com.example.backend.web.ErrorNamespace;
import com.example.backend.web.ErrorNamespaces;
import com.example.backend.web.NamespaceAdvice;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

@com.tngtech.archunit.junit.AnalyzeClasses(
    packages = "com.example.backend",
    importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class ArchitectureTest {

    // Cycles

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_cyclic_dependencies =
        SlicesRuleDefinition.slices()
            .matching("com.example.backend.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("Cyclic dependencies prevent independent module development and deployment");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_package_cycles =
        SlicesRuleDefinition.slices()
            .matching("com.example.backend.(**)")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("A cycle between two packages, even inside one module, means neither can be"
                    + " understood or changed without the other");

    // Naming Conventions

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_classes_in_default_package =
        noClasses()
            .should().haveNameMatching("^[^.]+$")
            .allowEmptyShould(true)
            .as("No class should reside in the default (unnamed) package");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_controller =
        classes()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().haveSimpleNameEndingWith("Controller")
            .allowEmptyShould(true)
            .because("Naming conventions aid discoverability, whichever package a controller lives in");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_service =
        classes()
            .that().resideInAPackage("..application")
            .and().areAnnotatedWith(Service.class)
            .and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("Service")
            .allowEmptyShould(true)
            .because("An application-layer bean is a use-case service and is named as one");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_adapter =
        classes()
            .that().resideInAPackage("..infrastructure..")
            .and().areTopLevelClasses()
            .and().areMetaAnnotatedWith(Component.class)
            .should().haveSimpleNameEndingWith("Adapter")
            .allowEmptyShould(true)
            .because("An infrastructure bean implements a port for the layers inside it and is"
                    + " named as an adapter");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_jpa_repository =
        classes()
            .that().areInterfaces()
            .and().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().haveSimpleNameEndingWith("JpaRepository")
            .allowEmptyShould(true)
            .because("A Spring Data repository is told apart from the domain repository port it"
                    + " backs by its name");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_entity =
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().haveSimpleNameEndingWith("Entity")
            .allowEmptyShould(true)
            .because("A persistence entity is told apart from the domain type it maps by its name");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_configuration =
        classes()
            .that().areAnnotatedWith(Configuration.class)
            .should().haveSimpleNameEndingWith("Config")
            .orShould().haveSimpleNameEndingWith("Configuration")
            .allowEmptyShould(true)
            .because("Naming conventions aid discoverability");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule exception_naming_convention =
        classes()
            .that().areAssignableTo(Exception.class)
            .should().haveSimpleNameEndingWith("Exception")
            .allowEmptyShould(true)
            .because("Exception naming must be explicit");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule exception_names_are_exceptions =
        classes()
            .that().haveSimpleNameEndingWith("Exception")
            .should().beAssignableTo(Throwable.class)
            .allowEmptyShould(true)
            .because("A class named *Exception that cannot be thrown misleads every reader");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_generic_exception_throws =
        noMethods()
            .should().declareThrowableOfType(Exception.class)
            .orShould().declareThrowableOfType(Throwable.class)
            .allowEmptyShould(true)
            .because("A method that throws Exception tells its caller nothing about what can go wrong");

    // Class Containment

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_controller =
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..controller")
            .allowEmptyShould(true)
            .because("Web adapters live in the module's controller package");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_service =
        classes()
            .that().haveSimpleNameEndingWith("Service")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..application")
            .allowEmptyShould(true)
            .because("Use-case services live in the module's application package");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_adapter =
        classes()
            .that().haveSimpleNameEndingWith("Adapter")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true)
            .because("Adapters live in the module's infrastructure packages");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_jpa_repository =
        classes()
            .that().haveSimpleNameEndingWith("JpaRepository")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..infrastructure.persistence")
            .allowEmptyShould(true)
            .because("Spring Data repositories are a persistence adapter detail");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule configuration_classes_in_config_package =
        classes()
            .that().areAnnotatedWith(Configuration.class)
            .should().resideInAPackage("..config..")
            .allowEmptyShould(true)
            .because("Configuration classes must be isolated in config packages");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule entities_only_in_entity_packages =
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..entity..")
            .allowEmptyShould(true)
            .because("Entity classes belong to the persistence adapter, never the framework-free domain");

    // Layer Boundaries

    /**
     * The infrastructure adapters the onion rule knows, by their package under
     * {@code ..infrastructure}. Declared ahead of the rules that read it, which are initialized in
     * the order they are written.
     */
    private static final List<String> INFRASTRUCTURE_ADAPTERS =
            List.of("persistence", "session", "transaction", "request", "alert");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule onion_architecture = withInfrastructureAdapters(
        onionArchitecture()
            .domainModels("com.example.backend..domain..")
            .domainServices("com.example.backend..domain.service..")
            .applicationServices("com.example.backend..application.."))
            .adapter("web", "com.example.backend..controller..")
            .adapter("config", "com.example.backend..config..")
            .withOptionalLayers(true)
            .because("Dependencies point inward: adapters depend on application, application on domain, domain on nothing");

    private static Architectures.OnionArchitecture withInfrastructureAdapters(
            Architectures.OnionArchitecture onion) {
        for (String adapter : INFRASTRUCTURE_ADAPTERS) {
            onion = onion.adapter(adapter, "com.example.backend..infrastructure." + adapter + "..");
        }
        return onion;
    }

    // The onion rule reads the adapters it is given and nothing else: a package such as
    // ..infrastructure.http would be in no layer, so the application layer could depend on it
    // unseen. A new adapter package fails here until it is added to INFRASTRUCTURE_ADAPTERS.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_infrastructure_package_is_a_declared_adapter =
        classes()
            .that().resideInAPackage("com.example.backend..infrastructure..")
            .should().resideInAnyPackage(INFRASTRUCTURE_ADAPTERS.stream()
                    .map(adapter -> "com.example.backend..infrastructure." + adapter + "..")
                    .toArray(String[]::new))
            .allowEmptyShould(true)
            .because("an infrastructure package the onion rule does not name is in no layer, so"
                    + " nothing holds its dependencies inward");

    // The onion rule above cannot see this: the auth.epic root matches no layer, yet it holds
    // adapter-grade code (Micrometer meters, key signing, bound properties).
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule inner_layers_never_reach_the_epic_root =
        noClasses()
            .that().resideInAnyPackage("com.example.backend..application..", "com.example.backend..domain..")
            .should().dependOnClassesThat().resideInAPackage("com.example.backend.auth.epic")
            .because("Dependencies point inward: the Epic package root is adapter code, which the"
                    + " application and domain layers must not depend on");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_domain_infrastructure_imports =
        noClasses()
            .that().resideInAnyPackage("..domain..", "..model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..infrastructure..", "..adapter..", "..web..", "..controller..",
                    "..config..", "..application..")
            .allowEmptyShould(true)
            .because("Domain must remain infrastructure-agnostic");

    // The onion rule above cannot see this either: web is a layer-less root holding the HTTP error
    // plumbing, adapter-grade code the application layer has no business reaching.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule inner_layers_never_reach_the_web_root =
        noClasses()
            .that().resideInAnyPackage("com.example.backend..application..", "com.example.backend..domain..")
            .should().dependOnClassesThat().resideInAPackage("com.example.backend.web..")
            .because("Dependencies point inward: the web package is the API's error and SPA plumbing,"
                    + " which the application and domain layers must not depend on");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_domain_framework_imports =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.servlet..", "org.hibernate..")
            .allowEmptyShould(true)
            .because("The domain is plain Java, so it can be tested and reasoned about without a framework");

    // The rule above reads direct dependencies only, and the domain reaches the layer-less
    // observability root (LogEvent), where framework-bound classes sit beside plain ones.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_domain_framework_dependencies_transitively =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().transitivelyDependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.servlet..", "org.hibernate..")
            .allowEmptyShould(true)
            .because("The domain is plain Java all the way down: a shared type it uses must not carry"
                    + " a framework in with it");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_spring_annotations_in_domain =
        noClasses()
            .that().resideInAnyPackage("..entity..", "..model..", "..domain..")
            .and().resideOutsideOfPackage("..mapper..")
            .and().areTopLevelClasses()
            .should().beAnnotatedWith(Component.class)
            .orShould().beAnnotatedWith(Service.class)
            .orShould().beAnnotatedWith(Repository.class)
            .orShould().beAnnotatedWith(Controller.class)
            .allowEmptyShould(true)
            .because("Domain objects must not carry Spring stereotype annotations");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_business_logic_in_controllers =
        noClasses()
            .that().resideInAPackage("..controller..")
            .or().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..repository..", "..persistence..", "com.example.backend..dao..")
            .allowEmptyShould(true)
            .because("Controllers must delegate to services, not access repositories directly");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_transactional_outside_service =
        noClasses()
            .that().resideInAnyPackage("..controller..", "..domain..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .orShould().beAnnotatedWith("jakarta.transaction.Transactional")
            .allowEmptyShould(true)
            .because("Transaction management belongs at the use-case/service boundary");

    // The rule above reads class-level annotations only, and this codebase declares
    // @Transactional on methods, so a transactional handler would pass it unseen.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_transactional_handler_methods =
        noMethods()
            .that().areDeclaredInClassesThat().resideInAnyPackage("..controller..", "..domain..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .orShould().beAnnotatedWith("jakarta.transaction.Transactional")
            .allowEmptyShould(true)
            .because("Transaction management belongs at the use-case/service boundary, method by"
                    + " method as much as class by class");

    // The deadlock retry (ADR 0013's as-13)

    /**
     * The public methods that open a transaction which may write and are deliberately NOT retried
     * on deadlock, each with the reason, written once here.
     *
     * <p>ADR 0013's as-13 retries "a SCIM or administrative write", once, at its outermost
     * transactional boundary, and never "read-only and scheduled paths: the next request or run
     * covers them". Every other public {@code @Transactional} method that is not read-only carries
     * {@link com.example.backend.observability.RetriedOnDeadlock}, which
     * {@link #every_transactional_write_is_retried_on_deadlock} holds: a new write is retried
     * unless someone adds it here with a reason. An entry is the owner's name and the method's,
     * {@code Owner#method}, covering every overload of it.
     */
    static final Map<String, String> NOT_RETRIED_ON_DEADLOCK = Map.ofEntries(
            // The login-state events: the Login and the password change record what the attempt
            // did to the login state (ADR 0001). They are neither SCIM nor administrative writes,
            // and the person signing in, whose attempt this is, tries again.
            Map.entry("com.example.backend.auth.application.LoginAttemptService#recordFailure",
                    "a refused Login's login-state event, not a SCIM or administrative write"),
            Map.entry("com.example.backend.auth.application.LoginAttemptService#recordPasswordSuccess",
                    "an accepted Login's login-state event, not a SCIM or administrative write"),
            Map.entry("com.example.backend.auth.application.LoginAttemptService#recordEpicSuccess",
                    "an accepted Epic Login's login-state event, not a SCIM or administrative write"),
            Map.entry("com.example.backend.auth.application.LoginAttemptService"
                            + "#recordPasswordChangeFailure",
                    "a refused password change's login-state event, not a SCIM or administrative"
                            + " write"),
            Map.entry("com.example.backend.auth.application.LoginService#logInFromEpic",
                    "an Epic Login (ADR 0012): a login-state event, not a SCIM or administrative"
                            + " write; the clinician launches again"),
            Map.entry("com.example.backend.auth.application.PasswordChangeService#changePassword",
                    "the signed-in User's own password change: self-service, not a SCIM or"
                            + " administrative write; the User submits it again"),
            // The SCIM reads: their transaction writes only the read's own fail-closed audit row
            // (SCIM_*_QUERIED), so they are as-13's read-only paths, which the next request covers.
            Map.entry("com.example.backend.scim.application.ScimUserService#query",
                    "a SCIM read, writing only its own audit row: read-only in as-13's sense"),
            Map.entry("com.example.backend.scim.application.ScimGroupService#query",
                    "a SCIM read, writing only its own audit row: read-only in as-13's sense"),
            Map.entry("com.example.backend.scim.application.ScimSearchService#search",
                    "a SCIM read, writing only its own audit row: read-only in as-13's sense"),
            // The scheduled and startup paths, which the next run or start covers.
            Map.entry("com.example.backend.scim.application.ScimSeedService#seed",
                    "startup seeding, before any request is served: a failure fails the start,"
                            + " and the next start seeds"),
            Map.entry("com.example.backend.scim.application.ScimSeedService#seedDevFixtures",
                    "startup seeding of the development fixtures: the next start seeds"),
            Map.entry("com.example.backend.scim.application.ScimSeedService#seedDormantDevFixture",
                    "startup seeding of the development fixtures: the next start seeds"),
            Map.entry("com.example.backend.scim.application.ScimSeedService#seedGroupPermissionFixtures",
                    "startup seeding of the development fixtures: the next start seeds"),
            Map.entry("com.example.backend.patient.application.PatientSeedService#seedDevFixtures",
                    "startup seeding of the development patient fixtures: the next start seeds"),
            Map.entry("com.example.backend.scim.application.ScimMembershipRevocationService"
                            + "#revokeMemberships",
                    "the dormancy job's membership revocation, joining the run's batch transaction: a"
                            + " scheduled path, which the next run covers"),
            Map.entry("com.example.backend.audit.infrastructure.persistence.AuditEventRetentionAdapter"
                            + "#deleteBatchOccurredBefore",
                    "a batch of the audit retention job's delete: a scheduled path, which the next"
                            + " run covers"),
            Map.entry("com.example.backend.scim.infrastructure.persistence.ScimSeedLockAdapter#acquire",
                    "MANDATORY propagation: it only ever joins the seed's transaction, so it is never"
                            + " the outermost boundary a retry belongs at"));

    /** Spring's {@code @Transactional} on a method, or else on the class declaring it. */
    private static Optional<org.springframework.transaction.annotation.Transactional> transactionOf(
            JavaMethod method) {
        Class<org.springframework.transaction.annotation.Transactional> type =
                org.springframework.transaction.annotation.Transactional.class;
        if (method.isAnnotatedWith(type)) {
            return Optional.of(method.getAnnotationOfType(type));
        }
        return method.getOwner().isAnnotatedWith(type)
                ? Optional.of(method.getOwner().getAnnotationOfType(type))
                : Optional.empty();
    }

    /** A public method that opens, or joins, a transaction that is not read-only. */
    private static boolean isTransactionalWrite(JavaMethod method) {
        return method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)
                && transactionOf(method).filter(transaction -> !transaction.readOnly()).isPresent();
    }

    /** {@code Owner#method}, the key {@link #NOT_RETRIED_ON_DEADLOCK} names a method by. */
    private static String retryKey(JavaMethod method) {
        return method.getOwner().getName() + "#" + method.getName();
    }

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_transactional_write_is_retried_on_deadlock =
        methods()
            .that(DescribedPredicate.describe(
                    "are public and open a transaction that is not read-only",
                    ArchitectureTest::isTransactionalWrite))
            .and(DescribedPredicate.describe("are not exempted in NOT_RETRIED_ON_DEADLOCK",
                    (JavaMethod method) -> !NOT_RETRIED_ON_DEADLOCK.containsKey(retryKey(method))))
            .should().beAnnotatedWith(com.example.backend.observability.RetriedOnDeadlock.class)
            .because("ADR 0013's as-13: every SCIM or administrative write is retried once on"
                    + " deadlock; a write that is not says why in NOT_RETRIED_ON_DEADLOCK");

    /** An exemption names a write that exists and is not retried, so none outlives its method. */
    @com.tngtech.archunit.junit.ArchTest
    static void every_deadlock_retry_exemption_names_an_unretried_write(
            com.tngtech.archunit.core.domain.JavaClasses classes) {
        Set<String> unretried = new java.util.HashSet<>();
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                if (isTransactionalWrite(method)
                        && !method.isAnnotatedWith(
                                com.example.backend.observability.RetriedOnDeadlock.class)) {
                    unretried.add(retryKey(method));
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(unretried)
                .as("NOT_RETRIED_ON_DEADLOCK names only public, non-read-only @Transactional"
                        + " methods that are not retried")
                .containsAll(NOT_RETRIED_ON_DEADLOCK.keySet());
    }

    /**
     * The retry is placed where a whole transaction begins: on a public method that opens one of
     * its own. Each attempt then runs in a transaction of its own, which is what makes running it
     * again safe — a retry around a method that joins or needs none would repeat half a
     * transaction, or work that was never transactional at all.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_retried_write_opens_its_own_transaction =
        methods()
            .that().areAnnotatedWith(com.example.backend.observability.RetriedOnDeadlock.class)
            .should(new ArchCondition<JavaMethod>(
                    "be public and open a read-write transaction of their own") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean opens = isTransactionalWrite(method) && transactionOf(method)
                            .map(transaction -> transaction.propagation()
                                    == org.springframework.transaction.annotation.Propagation.REQUIRED
                                    || transaction.propagation()
                                    == org.springframework.transaction.annotation.Propagation
                                            .REQUIRES_NEW)
                            .orElse(false);
                    if (!opens) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " is retried but does not open a read-write transaction"));
                    }
                }
            })
            .because("ADR 0013's as-13: the retry sits at the outermost transactional boundary,"
                    + " ahead of the transaction it runs again");

    /**
     * Outermost placement, made checkable: a retried write is called by an inbound web adapter
     * alone, which opens no transaction ({@link #no_transactional_handler_methods},
     * {@link #web_adapters_open_no_transaction}), so it is always the outermost transactional
     * boundary. A retried method called from inside a transaction would join it, and
     * {@code DeadlockRetryAdapter} passes such a call straight through — not retried and not
     * logged, since a transaction cannot be run again from inside itself. A call from the write's
     * own class would not pass through the proxy at all.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_retried_write_is_called_only_by_a_web_adapter =
        noClasses()
            .that().resideOutsideOfPackage("..controller..")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is retried on deadlock",
                    (JavaMethodCall call) -> call.getTarget().resolveMember()
                            .map(target -> target.isAnnotatedWith(
                                    com.example.backend.observability.RetriedOnDeadlock.class))
                            .orElse(false)))
            .because("ADR 0013's as-13: the retry is never nested, so no code that may run inside"
                    + " a transaction calls a retried write");

    /**
     * No call reaches a retried write unseen: it implements and overrides nothing, so every call
     * names its own class, which {@link #a_retried_write_is_called_only_by_a_web_adapter} reads.
     * A port method a retried write implemented could be called through the port from inside a
     * transaction, and the rule above would never see the call.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_retried_write_overrides_nothing =
        methods()
            .that().areAnnotatedWith(com.example.backend.observability.RetriedOnDeadlock.class)
            .should(new ArchCondition<JavaMethod>("implement or override no inherited method") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    List<String> parameters = method.getRawParameterTypes().stream()
                            .map(JavaClass::getName).toList();
                    java.util.stream.Stream.concat(
                                    method.getOwner().getAllRawInterfaces().stream(),
                                    method.getOwner().getAllRawSuperclasses().stream())
                            .flatMap(inherited -> inherited.getMethods().stream())
                            .filter(inherited -> inherited.getName().equals(method.getName())
                                    && inherited.getRawParameterTypes().stream()
                                            .map(JavaClass::getName).toList().equals(parameters))
                            .forEach(inherited -> events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " is retried and overrides "
                                            + inherited.getFullName())));
                }
            })
            .because("every call to a retried write is one the outermost-placement rule can read");

    /**
     * A fail-open audit event ends the retried body that appends it: after it, no path reaches a
     * port or another bean before it throws or returns.
     *
     * <p>A fail-open event — a refusal's, {@code Audited.Refused} — is appended in a transaction of
     * its own and commits at once (ADR 0004). Anything the body did after it could be a deadlock's
     * victim, and the retry would then run the body again and append the event a second time: a
     * duplicate audit row, which nothing removes. A refusal recorded as the body throws it is safe
     * — nothing follows that a deadlock could strike — and so is an event appended before writes
     * on a path that ends at it. {@link RetriedBodies} reads the paths from the bytecode, following
     * the body into its own helpers and the beans it calls.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_retried_write_ends_at_its_fail_open_audit_event =
        methods()
            .that().areAnnotatedWith(com.example.backend.observability.RetriedOnDeadlock.class)
            .should(new ArchCondition<JavaMethod>(
                    "reach no port or bean after appending a fail-open audit event") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    java.lang.reflect.Method reflected = method.reflect();
                    String descriptor = java.lang.invoke.MethodType.methodType(
                                    reflected.getReturnType(), reflected.getParameterTypes())
                            .toMethodDescriptorString();
                    RetriedBodies.collaboratorsAfterAFailOpenEvent(
                                    method.getOwner().getName().replace('.', '/'),
                                    method.getName(), descriptor)
                            .forEach(found -> events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + ": " + found)));
                }
            })
            .because("ADR 0013's as-13 retries a deadlocked write, and a fail-open audit event"
                    + " appended before more work would be appended again by the retry (ADR 0004)");

    /** A web adapter opens no transaction by hand either, around a retried write or otherwise. */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule web_adapters_open_no_transaction =
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat(DescribedPredicate.describe(
                    "open a transaction: a transaction manager, or a template or synchronization"
                            + " from org.springframework.transaction.support",
                    (JavaClass type) -> type.getPackageName().equals(
                                    "org.springframework.transaction.support")
                            || type.isAssignableTo(
                                    org.springframework.transaction.TransactionManager.class)))
            .because("ADR 0013's as-13: a retried write called inside a handler's own transaction"
                    + " would be nested, and not retried");

    /**
     * Time is read through the injected {@code Clock} bean.
     *
     * <p>Lockout, dormancy, audit retention and the session lifetime are all decided against the
     * clock, and their tests pin it. A use case that read the system clock itself would decide
     * against a time no test controls. Configuration is exempt: it is where the {@code Clock} bean
     * is built and where startup timing is logged.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule time_is_read_through_the_clock_bean =
        noClasses()
            .that().resideOutsideOfPackage("..config..")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "a no-argument now() on a java.time type, a system Clock, or"
                            + " System.currentTimeMillis",
                    (JavaMethodCall call) -> {
                        JavaClass owner = call.getTargetOwner();
                        String name = call.getName();
                        return (owner.getPackageName().equals("java.time")
                                        && name.equals("now")
                                        && call.getTarget().getRawParameterTypes().isEmpty())
                                || (owner.isEquivalentTo(java.time.Clock.class)
                                        && name.startsWith("system"))
                                || (owner.isEquivalentTo(System.class)
                                        && name.equals("currentTimeMillis"));
                    }))
            .allowEmptyShould(true)
            .because("Time-driven rules are decided against the injected Clock, which tests pin");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_jpa_outside_persistence_adapters =
        noClasses()
            .that().resideOutsideOfPackage("..infrastructure.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "org.hibernate..")
            .allowEmptyShould(true)
            .because("JPA, Spring Data and Hibernate are a persistence adapter's implementation detail");

    // ADR 0010: every protected handler declares its Permission with method security, beside the
    // code it protects; the chain repeats it as a backstop. One mechanism, on handlers only.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule one_method_security_mechanism =
        noMethods()
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PostAuthorize")
            .orShould().beAnnotatedWith("org.springframework.security.access.annotation.Secured")
            .orShould().beAnnotatedWith("jakarta.annotation.security.RolesAllowed")
            .allowEmptyShould(true)
            .because("ADR 0010: a Permission is declared with @PreAuthorize alone, so every"
                    + " declaration reads the same way and the authorization contract test finds them");

    // ADR 0010: the declaration sits on the web adapter's handler, never deeper or on a type
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule permissions_are_declared_on_handlers =
        methods()
            .that().areAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .should().beDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .andShould().beDeclaredInClassesThat().resideInAPackage("..controller..")
            .because("ADR 0010: each protected operation declares its Permission where the"
                    + " operation is defined, so the requirement is read next to the route");

    // ADR 0010: a class-level declaration would silently cover a handler added later
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_class_level_method_security =
        noClasses()
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .orShould().beAnnotatedWith("org.springframework.security.access.annotation.Secured")
            .orShould().beAnnotatedWith("jakarta.annotation.security.RolesAllowed")
            .allowEmptyShould(true)
            .because("ADR 0010: every operation names its own Permission; a class-wide one would"
                    + " grant a handler added later a Permission nobody chose for it");

    /**
     * The session-chain adapters whose handlers need NO Permission: self-service (authenticated
     * only) and the public login surface. Every other application-chain handler is protected.
     */
    private static final Set<String> SELF_SERVICE_OR_PUBLIC_CONTROLLERS = Set.of(
            "com.example.backend.auth.controller.AuthController",
            "com.example.backend.auth.controller.SelfController",
            // Our public JWKS, which Epic fetches with no session (D14).
            "com.example.backend.auth.epic.controller.EpicJwksController",
            // The EHR launch, which the browser opens from Epic with no session of ours (D1).
            "com.example.backend.auth.epic.controller.EpicLaunchController");

    /**
     * The bearer-chain (SCIM) adapters whose handlers need a valid connector token and NO
     * Permission: discovery ({@code ServiceProviderConfig}, {@code Schemas}, {@code ResourceTypes})
     * and {@code /Me}. Every other SCIM handler — Users, Groups and the root {@code .search} —
     * declares its Permission as an application-chain handler does. Named one by one, so that a
     * new controller in the SCIM namespace is held to a Permission until someone lists it here.
     */
    private static final Set<String> TOKEN_ONLY_SCIM_CONTROLLERS = Set.of(
            "com.example.backend.scim.controller.ScimDiscoveryController",
            "com.example.backend.scim.controller.ScimMeController");

    // ADR 0010: a forgotten declaration fails closed at build time, not only at the chain. Both
    // chains: on the SCIM one the bearer filter's request-line rule is the backstop, and it reads
    // the path as sent rather than as the dispatcher matched it.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_protected_handler_declares_a_permission =
        methods()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.PostMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.PutMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.PatchMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.RequestMapping.class)
            .and().areDeclaredInClassesThat(new DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                    "are adapters of either chain that are neither self-service, public nor"
                            + " token-only") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaClass owner) {
                    return owner.isAnnotatedWith(RestController.class)
                            && !SELF_SERVICE_OR_PUBLIC_CONTROLLERS.contains(owner.getName())
                            && !TOKEN_ONLY_SCIM_CONTROLLERS.contains(owner.getName());
                }
            })
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .because("ADR 0010: every protected operation declares the Permission it requires;"
                    + " a handler added without one would otherwise be held only by the chain's"
                    + " backstop rule, if anyone remembered to write it");

    // Module Boundaries

    /**
     * Which modules each module may depend on: the whole inter-module graph, in one place.
     *
     * <p>An allowlist rather than a rule per module saying what it may not reach, because a
     * deny-list fails open: a module added later is in nobody's list, so it could depend on, and be
     * depended on by, anything. Here a new top-level package fails
     * {@link #every_top_level_package_is_a_declared_module} until it is given an entry, and a new
     * edge fails {@link #module_dependencies_follow_the_declared_graph} until it is added.
     */
    private static final Map<String, Set<String>> MODULE_DEPENDENCIES = Map.ofEntries(
            // The Permission vocabulary and what a Group's memberships confer are read by both the
            // session and the SCIM side, so they know neither; the stored Group Permissions are
            // scim's, beside the Groups they belong to.
            Map.entry("authorization", Set.of()),
            // The shared base every module logs through, so it knows none of them.
            Map.entry("observability", Set.of()),
            // The scheduled-job lock and run module are shared by the auth and audit jobs (ADR 0005),
            // so it knows neither of them. A job's one identity, ScheduledJob, carries the
            // Operation its records are classified as, so it depends on the logging base every
            // module depends on, which depends on nothing back. A run's transaction is the main
            // pool's, named by the qualifier the database module declares.
            Map.entry("scheduling", Set.of("database", "observability")),
            // The connections module, the main pool and the migrate step: how the service reaches
            // its database and how the schema gets there, which no business module is needed for.
            // It also owns the qualifiers its connection beans are injected by.
            Map.entry("database", Set.of()),
            // SPA routing and the API error shape are shared plumbing and know no business module.
            Map.entry("web", Set.of("observability")),
            // Shared by every business module, so it depends on none of them. Its append and
            // retention pools are the database connections module's.
            Map.entry("audit", Set.of("authorization", "database", "observability", "scheduling")),
            // auth reaches the directory through scim.domain ports; nothing in scim mentions auth
            // (backend/AGENTS.md).
            // scim reaches web for the error namespaces: it contributes the SCIM namespace's
            // error document to the one selection every refusing layer asks, installs the one
            // sendError adapter in its chain, and marks its advice as that namespace's. It
            // reaches database for TimedJdbc: the seed lock's row-lock wait is bounded as every
            // lock statement is (ADR 0013, "Bounded timeouts"), as the scheduled-job lock's is.
            Map.entry("scim", Set.of("audit", "authorization", "database", "observability", "web")),
            Map.entry("auth", Set.of("audit", "authorization", "observability", "scheduling", "scim", "web")),
            // patient and lifecycle are leaves, which no entry names: removing one must not break
            // another module. patient may reach auth, and nothing else that holds state: not
            // scim, so the directory's seeding and the patient fixtures' stay apart. It reaches
            // observability for one thing: its seeder's INFO record is built with LogEvent, as
            // every record is (be-log-record-outside-log-event; ADR 0014, "The module's edges").
            Map.entry("patient", Set.of("auth", "observability")),
            Map.entry("lifecycle", Set.of("audit", "auth", "observability", "scim")));

    private static final String ROOT_PACKAGE = "com.example.backend";

    /** The module a class belongs to: the first package segment under the root, if any. */
    private static Optional<String> moduleOf(JavaClass type) {
        String packageName = type.getPackageName();
        if (!packageName.startsWith(ROOT_PACKAGE + ".")) {
            return Optional.empty();
        }
        String rest = packageName.substring(ROOT_PACKAGE.length() + 1);
        int dot = rest.indexOf('.');
        return Optional.of(dot < 0 ? rest : rest.substring(0, dot));
    }

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_top_level_package_is_a_declared_module =
        classes()
            .that().resideInAPackage(ROOT_PACKAGE + ".*..")
            .should(new ArchCondition<JavaClass>("belong to a module declared in MODULE_DEPENDENCIES") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    moduleOf(type)
                            .filter(module -> !MODULE_DEPENDENCIES.containsKey(module))
                            .ifPresent(module -> events.add(SimpleConditionEvent.violated(type,
                                    type.getName() + " is in module '" + module + "', which"
                                            + " MODULE_DEPENDENCIES does not declare")));
                }
            })
            .because("a module states what it may depend on before it exists, so the module graph"
                    + " fails closed");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule module_dependencies_follow_the_declared_graph =
        classes()
            .that().resideInAPackage(ROOT_PACKAGE + ".*..")
            .should(new ArchCondition<JavaClass>("depend only on modules declared for their own") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    String own = moduleOf(type).orElseThrow();
                    Set<String> allowed = MODULE_DEPENDENCIES.getOrDefault(own, Set.of());
                    for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                        moduleOf(dependency.getTargetClass())
                                .filter(target -> !target.equals(own) && !allowed.contains(target))
                                .ifPresent(target -> events.add(SimpleConditionEvent.violated(
                                        dependency,
                                        dependency.getDescription() + " (" + own + " -> " + target
                                                + " is not in MODULE_DEPENDENCIES)")));
                    }
                }
            })
            .because("the inter-module graph is declared in one place, and an edge nobody declared"
                    + " is a design change to make on purpose");

    /** The modules every business module may build on, and which therefore know none of them. */
    private static final Set<String> SHARED_MODULES =
            Set.of("audit", "authorization", "database", "observability", "scheduling", "web");

    // MODULE_DEPENDENCIES already implies this; stated as its own rule so that an edit to the map
    // cannot turn a shared module into a business module's dependant unremarked.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_shared_depends_on_feature =
        classes()
            .that().resideInAPackage(ROOT_PACKAGE + ".*..")
            .and(new DescribedPredicate<JavaClass>("are in a shared module") {
                @Override
                public boolean test(JavaClass type) {
                    return moduleOf(type).filter(SHARED_MODULES::contains).isPresent();
                }
            })
            .should(new ArchCondition<JavaClass>("depend on no business module") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                        moduleOf(dependency.getTargetClass())
                                .filter(target -> !SHARED_MODULES.contains(target))
                                .ifPresent(target -> events.add(SimpleConditionEvent.violated(
                                        dependency,
                                        dependency.getDescription() + " (shared module depends on"
                                                + " business module '" + target + "')")));
                    }
                }
            })
            .because("dependencies flow business -> shared, never back, or a shared module stops"
                    + " being reusable by the modules that build on it");

    /**
     * The shared modules with no layers of their own: each is one package of plumbing, reached
     * directly by every module that builds on it.
     */
    private static final Set<String> LAYERLESS_MODULES = Set.of("database", "observability", "web");

    /** The layers another module may be reached through: its ports and its use cases. */
    private static final Set<String> PUBLISHED_LAYERS = Set.of("domain", "application");

    /**
     * The classes that may reach another module's other layers, each with the reason. Named one by
     * one, so a new reach into another module's internals is a choice someone writes down here.
     */
    private static final Set<String> CROSS_MODULE_INTERNALS_EXEMPT = Set.of(
            // The startup record logs every module's effective settings, so it reads the two that
            // live in their module's adapter code: whether SCIM is open (scim.config) and the Epic
            // Login switch with its signing keys' kids (the auth.epic root).
            "com.example.backend.lifecycle.config.ApplicationLifecycleLog");

    /** The layer a class sits in within its module: the package segment after the module's. */
    private static Optional<String> layerOf(JavaClass type) {
        String packageName = type.getPackageName();
        return moduleOf(type).map(module -> {
            String rest = packageName.substring(Math.min(packageName.length(),
                    ROOT_PACKAGE.length() + module.length() + 2));
            int dot = rest.indexOf('.');
            return dot < 0 ? rest : rest.substring(0, dot);
        });
    }

    // MODULE_DEPENDENCIES says which modules a module may reach, not which of their layers, and
    // the onion rule sees one module's persistence adapter reaching another's as the same layer.
    // Without this, auth could use a scim JpaRepository directly and skip the scim.domain port
    // behind which the directory's locks and versions are taken.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule other_modules_are_reached_through_domain_or_application =
        classes()
            .that().resideInAPackage(ROOT_PACKAGE + ".*..")
            .and(DescribedPredicate.<JavaClass>describe("are not named in CROSS_MODULE_INTERNALS_EXEMPT",
                    type -> !CROSS_MODULE_INTERNALS_EXEMPT.contains(type.getName())))
            .should(new ArchCondition<JavaClass>("reach another module only through its domain or"
                    + " application layer") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    String own = moduleOf(type).orElseThrow();
                    for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                        JavaClass target = dependency.getTargetClass();
                        moduleOf(target)
                                .filter(module -> !module.equals(own)
                                        && !LAYERLESS_MODULES.contains(module))
                                .filter(module -> layerOf(target)
                                        .filter(PUBLISHED_LAYERS::contains).isEmpty())
                                .ifPresent(module -> events.add(SimpleConditionEvent.violated(
                                        dependency,
                                        dependency.getDescription() + " (" + own + " reaches "
                                                + module + " outside its domain and application"
                                                + " layers)")));
                    }
                }
            })
            .because("a module is reached through its ports and use cases, so the rules behind them"
                    + " hold for every caller");

    /**
     * Outbound HTTP lives in the Epic adapter.
     *
     * <p>ADR 0012: Epic is reached through the one outbound client, {@code epicRestClient}, which
     * carries the timeouts (D25), the outbound log line and the call metrics. A second client
     * elsewhere would make calls with none of the three.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule one_outbound_http_client =
        noClasses()
            .that().resideOutsideOfPackage("com.example.backend.auth.epic..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..",
                    "java.net.http..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
            .allowEmptyShould(true)
            .because("ADR 0012: every outbound call goes through epicRestClient, which carries the"
                    + " timeouts, logging and metrics");

    // Error namespaces

    /**
     * Every namespace this service serves, as the running service holds them: the application
     * API's two and the SCIM module's contribution.
     */
    private static final ErrorNamespaces ERROR_NAMESPACES =
            new ErrorNamespaces(List.of(new ScimSecurityConfig().scimErrorNamespace()));

    /**
     * Every route a controller maps is in an error namespace, so a handler's failure gets its
     * namespace's document, from its namespace's advice, whichever module the handler lives in.
     * The request's path decides, not the handler's package: connector administration, in
     * {@code scim} but serving {@code /api}, is answered as the application API, which a rule
     * below pins by name.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_controller_route_is_in_an_error_namespace =
        classes()
            .that().areAnnotatedWith(RestController.class)
            .and().areNotAnnotatedWith(RestControllerAdvice.class)
            .should(new ArchCondition<JavaClass>("map only routes in an error namespace") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (String route : routes(type.reflect())) {
                        if (ERROR_NAMESPACES.of(route).isEmpty()) {
                            events.add(SimpleConditionEvent.violated(type, type.getName()
                                    + " maps " + route + ", which is in no error namespace"));
                        }
                    }
                }
            })
            .because("a handler's failure is answered in the document of the namespace its route is"
                    + " in, by that namespace's advice (NamespaceExceptionResolver)");

    /**
     * Every error namespace is answered by exactly one advice, and every advice's names are
     * namespaces.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_error_namespace_is_answered_by_exactly_one_advice =
        classes()
            .that().areAnnotatedWith(NamespaceAdvice.class)
            .should(new ArchCondition<JavaClass>("together answer every error namespace once") {
                private final Map<String, List<String>> answering = new HashMap<>();

                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (String path : type.reflect().getAnnotation(NamespaceAdvice.class).value()) {
                        if (ERROR_NAMESPACES.all().stream()
                                .noneMatch(namespace -> namespace.path().equals(path))) {
                            events.add(SimpleConditionEvent.violated(type, type.getName()
                                    + " answers " + path + ", which is no error namespace"));
                        }
                        answering.computeIfAbsent(path, any -> new ArrayList<>())
                                .add(type.getName());
                    }
                }

                @Override
                public void finish(ConditionEvents events) {
                    for (ErrorNamespace namespace : ERROR_NAMESPACES.all()) {
                        List<String> advices = answering.getOrDefault(namespace.path(), List.of());
                        if (advices.size() != 1) {
                            events.add(SimpleConditionEvent.violated(namespace, "the error"
                                    + " namespace " + namespace.path() + " is answered by "
                                    + advices + ", not exactly one advice"));
                        }
                    }
                }
            })
            .because("NamespaceExceptionResolver hands a handler's failure to the one advice of the"
                    + " request's namespace");

    /**
     * Connector administration is answered like every other {@code /api} operation — by the
     * application API's advice, in {@code ApiError} — though it lives in the {@code scim} module
     * beside the SCIM namespace's advice.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule connector_administration_is_answered_as_the_application_api =
        classes()
            .that().haveFullyQualifiedName(
                    "com.example.backend.scim.controller.AdminConnectorController")
            .should(new ArchCondition<JavaClass>("serve only the application API's namespace,"
                    + " answered by ApiExceptionHandler") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (String route : routes(type.reflect())) {
                        if (ERROR_NAMESPACES.of(route).orElse(null) != ErrorNamespaces.API) {
                            events.add(SimpleConditionEvent.violated(type, route
                                    + " is not in the application API's namespace"));
                        }
                    }
                    if (!Set.of(ApiExceptionHandler.class.getAnnotation(NamespaceAdvice.class)
                            .value()).contains(ErrorNamespaces.API_PATH)) {
                        events.add(SimpleConditionEvent.violated(type,
                                "ApiExceptionHandler does not answer " + ErrorNamespaces.API_PATH));
                    }
                }
            })
            .because("connector administration serves /api, so its refusals and faults are the"
                    + " application API's, never SCIM's");

    /**
     * A namespace advice declares the one scope every namespace advice shares — this application's
     * handlers — and no narrower one: a package, a handler class or an annotation would decide by
     * the handler, which is what the namespace decides instead.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule namespace_advices_are_not_scoped_by_the_handler =
        classes()
            .that().areAnnotatedWith(NamespaceAdvice.class)
            .should(new ArchCondition<JavaClass>("declare exactly the application's handlers as"
                    + " their scope") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    RestControllerAdvice advice =
                            type.reflect().getAnnotation(RestControllerAdvice.class);
                    boolean shared = advice != null
                            && List.of(advice.basePackages())
                                    .equals(List.of(NamespaceAdvice.APPLICATION_HANDLERS))
                            && advice.value().length == 0
                            && advice.basePackageClasses().length == 0
                            && advice.assignableTypes().length == 0
                            && advice.annotations().length == 0;
                    if (!shared) {
                        events.add(SimpleConditionEvent.violated(type, type.getName()
                                + " is a namespace advice scoped other than to "
                                + NamespaceAdvice.APPLICATION_HANDLERS));
                    }
                }
            })
            .because("the request's namespace, not the handler's package or class, picks the advice");

    /** The one sendError adapter: nothing else rewrites a response's errors. */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule one_send_error_adapter =
        classes()
            .that().areAssignableTo(HttpServletResponseWrapper.class)
            .should().beNestedClasses()
            .andShould(new ArchCondition<JavaClass>("be ErrorDocumentFilter's") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    if (!type.getEnclosingClass()
                            .map(enclosing -> enclosing.isEquivalentTo(ErrorDocumentFilter.class))
                            .orElse(false)) {
                        events.add(SimpleConditionEvent.violated(type, type.getName()
                                + " wraps a response outside ErrorDocumentFilter"));
                    }
                }
            })
            .allowEmptyShould(true)
            .because("every namespace's sendError is rendered by ErrorDocumentFilter, asking"
                    + " ErrorNamespaces for the document");

    /** Every route {@code controller} maps: its base paths joined to each handler's own. */
    private static List<String> routes(Class<?> controller) {
        RequestMapping base =
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        List<String> prefixes = base == null || base.path().length == 0
                ? List.of("")
                : List.of(base.path());
        List<String> routes = new ArrayList<>();
        for (java.lang.reflect.Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping =
                    AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            List<String> paths = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
            for (String prefix : prefixes) {
                for (String path : paths) {
                    routes.add(prefix + path);
                }
            }
        }
        return routes;
    }

    // Dependency Injection

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_field_injection =
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION
            .allowEmptyShould(true)
            .because("Constructor injection is required for testability and immutability; this"
                    + " covers @Value, @Inject and @Resource fields as well as @Autowired");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_new_inside_service =
        noClasses()
            .that().resideInAPackage("..application")
            .and().areTopLevelClasses()
            .should().callConstructorWhere(new DescribedPredicate<JavaConstructorCall>(
                    "the target is a Service, Repository or Adapter") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    String name = call.getTargetOwner().getSimpleName();
                    return name.endsWith("Service") || name.endsWith("Repository") || name.endsWith("Adapter");
                }
            })
            .allowEmptyShould(true)
            .because("Collaborators are injected, so a service can be tested with a stand-in for each");

    // Code Quality

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule utility_classes_private_constructor =
        classes()
            .that().haveSimpleNameEndingWith("Util")
            .or().haveSimpleNameEndingWith("Utils")
            .should().haveOnlyPrivateConstructors()
            .andShould().haveModifier(com.tngtech.archunit.core.domain.JavaModifier.FINAL)
            .allowEmptyShould(true)
            .because("Utility classes must not be instantiable or extended");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_test_imports_in_production =
        noClasses()
            .that().resideOutsideOfPackages("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("org.junit..", "org.mockito..", "org.testng..")
            .allowEmptyShould(true)
            .because("Test dependencies must not leak into production code");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_system_out =
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .allowEmptyShould(true);

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_generic_exceptions_thrown =
        GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS
            .allowEmptyShould(true)
            .because("A named exception says what went wrong; a bare RuntimeException says nothing"
                    + " a handler or a reader can act on");

    // ADR 0003: every log line goes through SLF4J, which is where ECS formatting and redaction apply
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_java_util_logging =
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
            .allowEmptyShould(true)
            .because("ADR 0003: a java.util.logging record bypasses the ECS layout and its redaction");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_dependency_on_deprecated =
        noClasses()
            .should().dependOnClassesThat().areAnnotatedWith(Deprecated.class)
            .allowEmptyShould(true)
            .because("Deprecated code is on its way out; new callers keep it alive");

    // JPA / Persistence

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule entities_have_required_annotations =
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().beAnnotatedWith(Table.class)
            .allowEmptyShould(true)
            .because("Explicit table mapping prevents runtime surprises");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_entity_in_controllers =
        noClasses()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .allowEmptyShould(true)
            .because("Controllers must use DTOs, not entities, to prevent lazy-loading issues and data exposure");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule repositories_must_be_interfaces =
        classes()
            .that().resideInAnyPackage("..domain", "..infrastructure.persistence")
            .and().haveSimpleNameEndingWith("Repository")
            .and().areTopLevelClasses()
            .should().beInterfaces()
            .allowEmptyShould(true)
            .because("Domain repositories are ports and Spring Data repositories are generated;"
                    + " both are interfaces");

    // Spring Annotations

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule controllers_must_be_annotated =
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .and().areTopLevelClasses()
            .and().areNotInterfaces()
            .should().beAnnotatedWith(RestController.class)
            .orShould().beAnnotatedWith(Controller.class)
            .allowEmptyShould(true)
            .because("Controllers must be annotated for Spring component scanning");

    // ADR-derived

    /**
     * The logging context is written through one class or not at all.
     *
     * <p>Logs must carry correlation ids and never a userName, filter expression,
     * password, bearer value, hash or cookie value. That is a property of every
     * call site at once, so it cannot be held by reviewing them: scattered
     * {@code MDC.put} calls would each need checking, and a new one would be added
     * by someone who never read this rule. {@code LogContext} exposes three named
     * setters and no general-purpose one, so with this rule in force "what can
     * enter the logging context" has a single, readable answer.
     *
     * <p>The exemption is written as a name pattern rather than one fully qualified
     * name because {@code LogContext.Scope} — the nested class whose {@code close()}
     * restores a key's previous value — is a class of its own to ArchUnit. Naming
     * only the outer class would fail the rule on the contract's own
     * implementation, so the pattern covers {@code LogContext} and its nested
     * classes and nothing else.
     */
    // ADR 0003: ECS structured logging with redaction
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule mdc_is_only_touched_by_the_log_context =
        noClasses()
            .that().haveNameNotMatching("com\\.example\\.backend\\.observability\\.LogContext(\\$.*)?")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.slf4j.MDC")
            .allowEmptyShould(true)
            .because("LogContext is the only way anything writes to the logging context, so"
                    + " the three permitted keys are the only keys that exist");

    /**
     * The audit trail's boundary admits no free text.
     *
     * <p>Every audit-worthy event's actor and subject must be the account's stable
     * id, and no event body may carry a username, a password or a bearer value.
     * That is a property of every present and future call site at once, so it cannot
     * be held by reviewing them — the leak that matters is the event someone records
     * next year in a flow no test covers. It can be held by a shape: a username,
     * a password and a bearer value are all {@code String}s, so a boundary that
     * admits no {@code String} cannot be handed one.
     *
     * <p>The boundary is {@link com.example.backend.audit.domain.AuditTrail}'s one
     * {@code append} and the {@link com.example.backend.audit.domain.Audited} event
     * values it takes, so the rule holds both: no method or constructor of either
     * declares a parameter whose type mentions {@code String} — a {@code List<String>}
     * as much as a bare one — and no event holds a field of such a type. The event's
     * own textual fields are spelled inside the audit slice, from vocabularies it owns —
     * a resource type, a path name, a status class, an error code that is a reason
     * name. Adding a {@code String} component to an event, or a {@code String}
     * parameter to the trail, to pass one of them in from outside is what this rule
     * refuses. An event kind added later is held by it without being named here.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_audit_trail_boundary_admits_no_free_text =
        classes()
            .that().haveFullyQualifiedName("com.example.backend.audit.domain.AuditTrail")
            .or().areAssignableTo("com.example.backend.audit.domain.Audited")
            .should(new ArchCondition<JavaClass>("admit no String in a parameter or a field") {
                private boolean mentionsString(com.tngtech.archunit.core.domain.JavaType type) {
                    return type.getName().matches(".*\\bjava\\.lang\\.(String|CharSequence)\\b.*");
                }

                @Override
                public void check(JavaClass boundary, ConditionEvents events) {
                    boundary.getCodeUnits().forEach(unit -> unit.getParameterTypes().stream()
                        .filter(this::mentionsString)
                        .forEach(parameter -> events.add(SimpleConditionEvent.violated(
                            unit,
                            unit.getFullName() + " declares a parameter of type "
                                + parameter.getName() + "; an audit event's actor, subject"
                                + " and classification are ids and closed sets, and a String"
                                + " is how a username or a credential would get in"))));
                    boundary.getFields().stream()
                        .filter(field -> !field.getModifiers().contains(
                                com.tngtech.archunit.core.domain.JavaModifier.STATIC))
                        .filter(field -> mentionsString(field.getType()))
                        .forEach(field -> events.add(SimpleConditionEvent.violated(
                            field,
                            field.getFullName() + " holds a " + field.getType().getName()
                                + "; an audit event holds ids and closed sets only")));
                }
            })
            .because("A username, a password and a bearer value are all Strings, so the one"
                    + " boundary that records events admits none");

    /**
     * SCIM attribute facts have one owner: {@link com.example.backend.scim.domain.ScimResourceSchema}
     * declares every attribute — its type, case sensitivity, cardinality, mutability and
     * returnability — and discovery, request reading, projection, PATCH path classification and the
     * query vocabulary all read it from there. A class that built its own
     * {@code ScimAttribute} would be a second fact table, which is exactly what issue #104
     * removed: the two would drift, and discovery would advertise one comparison while a filter
     * applied another.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_resource_schema_defines_scim_attributes =
        noClasses()
            .that().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.ScimResourceSchema")
            .and().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.ScimAttribute")
            .should().callCodeUnitWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    new DescribedPredicate<com.tngtech.archunit.core.domain.AccessTarget>(
                            "a ScimAttribute constructor, factory or modifier") {
                        @Override
                        public boolean test(com.tngtech.archunit.core.domain.AccessTarget target) {
                            return target.getOwner().getFullName()
                                            .equals("com.example.backend.scim.domain.ScimAttribute")
                                    && java.util.Set.of("<init>", "singular", "complex",
                                            "multiValuedComplex", "asRequired", "asCaseExact", "unique",
                                            "canonical", "references")
                                            .contains(target.getName());
                        }
                    }))
            .allowEmptyShould(true)
            .because("attribute facts are declared once, in ScimResourceSchema, and every"
                    + " protocol path derives from that definition (issue #104)");

    /**
     * Password acceptance has one owner: {@link com.example.backend.scim.domain.PasswordAcceptance}
     * reads a User's password history to refuse reuse and records the accepted hash, and nothing
     * else in the service does either. A caller that reached the history port itself would be
     * writing its own reuse predicate or remembering a hash outside the acceptance sequence; the
     * persistence adapter that implements the port is the one other class that may name it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_password_acceptance_uses_the_password_history =
        noClasses()
            .that().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.PasswordAcceptance")
            .and().resideOutsideOfPackage("com.example.backend.scim.infrastructure.persistence..")
            .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo(
                                    "com.example.backend.scim.domain.ScimPasswordHistoryRepository"))))
            .allowEmptyShould(true)
            .because("callers decide on a new password through PasswordAcceptance, so reuse and"
                    + " remembering an accepted hash have one implementation (issue #103)");

    /**
     * Which resources a SCIM write advances has one owner,
     * {@code scim.domain.ResourceVersionAdvance}, and carrying that decision out has one too:
     * {@code scim.infrastructure.persistence.ResourceVersionStatements}, the only caller of the
     * bulk advance. An adapter describes the write, the domain module decides and the statements
     * apply its answer; an adapter that advanced versions itself would be working out who moved on
     * its own, which is how Group create with members once advanced none of them — and a miss is
     * silent, a stale ETag rather than an error. The decision itself is held infrastructure-free by
     * {@link #no_domain_infrastructure_imports}, so the in-memory stores the unit tests run against
     * can ask it too, rather than keeping a copy of the rule that could drift.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_resource_version_statements_advance_versions =
        noClasses()
            .that().doNotHaveFullyQualifiedName(
                    "com.example.backend.scim.infrastructure.persistence.ResourceVersionStatements")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is ScimResourceJpaRepository.advanceVersions",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().equals("advanceVersions")
                                    && call.getTargetOwner().isAssignableTo(
                                            "com.example.backend.scim.infrastructure.persistence"
                                                    + ".ScimResourceJpaRepository")))
            .allowEmptyShould(true)
            .because("ResourceVersionAdvance decides which resources' versions a write advances and"
                    + " ResourceVersionStatements alone applies it, so every write path applies one"
                    + " rule (issue #137, issue #114)");

    /**
     * A SCIM write's resource locks have one owner:
     * {@code scim.infrastructure.persistence.ScimWriteLockAdapter}, which takes every row a write
     * needs in one canonical order before the write changes anything — an order it decides itself
     * and issues one single-row lock per resource in, so no engine can reorder it. A second place
     * that locked resource rows would pick its own order, which is how a User DELETE and a Group
     * PUT on a shared membership deadlocked: each locked its own subject first and the other's
     * later.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_write_lock_module_locks_scim_resources =
        noClasses()
            .that().doNotHaveFullyQualifiedName(
                    "com.example.backend.scim.infrastructure.persistence.ScimWriteLockAdapter")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is ScimResourceJpaRepository.lockResource",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().equals("lockResource")
                                    && call.getTargetOwner().isAssignableTo(
                                            "com.example.backend.scim.infrastructure.persistence"
                                                    + ".ScimResourceJpaRepository")))
            .allowEmptyShould(true)
            .because("one module locks a SCIM write's rows, in one order, so no two writers can"
                    + " each hold a row the other waits for");

    /**
     * A connector token's row lock has the same owner as a SCIM write's:
     * {@code ScimWriteLockAdapter}, through the {@code ConnectorTokenLocks} port. A rotation and a
     * revocation take it before reading the token, so a second rotation of one token waits for the
     * first and is refused, rather than both minting a live replacement. One adapter issuing every
     * lock statement keeps the token lock and the resource locks from growing orders of their own.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_write_lock_module_locks_connector_tokens =
        noClasses()
            .that().doNotHaveFullyQualifiedName(
                    "com.example.backend.scim.infrastructure.persistence.ScimWriteLockAdapter")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is ScimConnectorTokenJpaRepository.lockById",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().equals("lockById")
                                    && call.getTargetOwner().isAssignableTo(
                                            "com.example.backend.scim.infrastructure.persistence"
                                                    + ".ScimConnectorTokenJpaRepository")))
            .allowEmptyShould(true)
            .because("one module takes the scim slice's locks, a connector token's included, so a"
                    + " rotation's lock is taken the one way, before the token is read");

    /**
     * A scheduled job's lock has one taker: the scheduled-job run module,
     * {@code scheduling.application.ScheduledJobRunService}, which opens the run's own transaction
     * on the primary data source, takes the lock in it and holds it until the body it hands the
     * job has ended (ADR 0005). A job body that took the lock itself would hold it for whatever
     * transaction it happened to be in — released after a first batch's commit, or at once in
     * autocommit on a data source whose transaction manager is not the lock's.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_scheduled_job_run_takes_a_job_lock =
        noClasses()
            .that().doNotHaveFullyQualifiedName(
                    "com.example.backend.scheduling.application.ScheduledJobRunService")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is ScheduledJobLock.tryAcquire",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().equals("tryAcquire")
                                    && call.getTargetOwner().isAssignableTo(
                                            "com.example.backend.scheduling.domain"
                                                    + ".ScheduledJobLock")))
            .allowEmptyShould(true)
            .because("the lock lives in the run's own transaction, which only the run module"
                    + " opens, so no job body can take it for a shorter one");

    // The rule above stops anyone else taking the lock; these two stop a job skipping it. A job is
    // scheduled one way, through a SchedulingConfigurer whose cron is read from configuration,
    // and @Scheduled cannot read one there (AuditRetentionScheduleConfig).
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_scheduled_annotation =
        noMethods()
            .should().beAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
            .allowEmptyShould(true)
            .because("ADR 0005: a job is scheduled through a SchedulingConfigurer that runs it"
                    + " through the scheduled-job run, so its lock serializes it across instances");

    // Reads the configurer's dependencies transitively, so it holds that the job reaches the run
    // module somewhere, not that the body it schedules is the one that does.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule scheduled_jobs_run_through_the_job_run =
        classes()
            .that().implement("org.springframework.scheduling.annotation.SchedulingConfigurer")
            .should().resideInAPackage("..config..")
            .andShould().transitivelyDependOnClassesThat().haveFullyQualifiedName(
                    "com.example.backend.scheduling.application.ScheduledJobRunService")
            .allowEmptyShould(true)
            .because("ADR 0005: every scheduled job takes its per-job lock row through the run"
                    + " module, or two instances run it at once");

    /** The login-state module, the one owner of the login-state writes. */
    private static final String LOGIN_STATE_MODULE =
            "com.example.backend.auth.application.LoginStateService";

    /** The port the login state is written and its dormancy candidates read through. */
    private static final String LOGIN_STATE_PORT =
            "com.example.backend.scim.domain.ScimLoginStateRepository";

    /**
     * What a newly imposed failure Lockout implies has one owner: the login-state module,
     * {@code auth.application.LoginStateService}. It writes the counted failure and, on the
     * transition into the lock, records {@code LOCKOUT_SET} and revokes the User's Sessions after
     * the commit. A counting path that recorded the Lockout itself would be repeating the edge
     * test, and a copy that forgot the revocation would read like its neighbour. The audit slice,
     * which declares the event, is the one other place that may construct it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_login_state_module_records_a_lockout =
        noClasses()
            .that().doNotHaveFullyQualifiedName(LOGIN_STATE_MODULE)
            .and().resideOutsideOfPackage("com.example.backend.audit..")
            .should().callConstructorWhere(DescribedPredicate.describe(
                    "target is the Audited.LockoutSet event",
                    (JavaConstructorCall call) -> call.getTargetOwner().isEquivalentTo(
                            com.example.backend.audit.domain.Audited.LockoutSet.class)))
            .allowEmptyShould(true)
            .because("the login-state module carries out what a newly imposed Lockout implies, so"
                    + " every counting path audits it and revokes Sessions the same way (issues"
                    + " #139, #42)");

    /**
     * The Failure run and the lock, the dormancy basis, the change-required flag and the completed
     * password change are written by the login-state module alone, anywhere in the application:
     * the Login, password-change, Unlock, forced-change and dormancy paths report what happened and
     * the module decides the new state (issues #42, #43). The directory reaches the module through
     * the {@code ScimUserLoginState} port it implements — so the development seed's dormant fixture
     * is written by the module too — and the persistence adapter, which implements these
     * operations, calls its JPA repository rather than the port. Every login-state write the port
     * has — the change write, the conditional lock and the completed password change — is guarded,
     * so the rule holds the whole boundary.
     *
     * <p>Not guarded here, because a call cannot say where its values came from:
     * {@code ScimUserRepository.create} and {@code replace}, which store a SCIM write's login state
     * with its profile in one row write. Their values are the module's decision, and
     * {@link #the_directory_decides_no_login_state} holds the directory to that.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_login_state_module_writes_the_login_state =
        noClasses()
            .that().resideInAPackage("com.example.backend..")
            .and().doNotHaveFullyQualifiedName(LOGIN_STATE_MODULE)
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is a ScimLoginStateRepository login-state write",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            Set.of("writeChange", "completePasswordChange", "imposeLock")
                                    .contains(call.getName())
                                    && call.getTargetOwner().isAssignableTo(
                                            LOGIN_STATE_PORT)))
            .allowEmptyShould(true)
            .because("one module decides the login state, so no path writes the failure run, the"
                    + " lock, the dormancy basis, the change-required flag or the completed"
                    + " password change its own way (issues #42, #43)");
    /**
     * The login-state port's clients are the login path's: the login-state module, which writes
     * through it, and the dormancy job, which reads its lockout candidates there — both in
     * {@code auth.application}. Nothing in the directory reaches it, a SCIM use case least of all:
     * a SCIM write asks the module through the {@code ScimUserLoginState} port and stores what it
     * answers through {@code ScimUserRepository}. The persistence adapter, which implements the
     * port, is the one other class that names it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_login_path_reaches_the_login_state_port =
        noClasses()
            .that().resideInAPackage("com.example.backend..")
            .and().resideOutsideOfPackages(
                    "com.example.backend.auth.application..",
                    "com.example.backend.scim.infrastructure.persistence..")
            .and().doNotHaveFullyQualifiedName(LOGIN_STATE_PORT)
            .should().dependOnClassesThat().haveFullyQualifiedName(LOGIN_STATE_PORT)
            .because("the directory leaves the login state to the login path, so no SCIM service"
                    + " writes or reads it through a port of its own (issue #116)");

    /**
     * The directory decides no login state: nothing in {@code scim} outside the value itself
     * applies one of {@code ScimLoginState}'s transitions. A SCIM create or replacement — PUT and
     * PATCH alike — asks the login-state module through the {@code ScimUserLoginState} port and
     * stores what it answers, so a Reactivation's reset basis and required change, and a
     * connector-set password's required change, are decided once, in the module. A use case, the
     * persistence adapter or a query that applied a transition of its own would be a second copy
     * of a rule, which is what issue #43 removed.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_directory_decides_no_login_state =
        noClasses()
            .that().resideInAPackage("com.example.backend.scim..")
            .and().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.ScimLoginState")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is a ScimLoginState transition",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().startsWith("with")
                                    && call.getTargetOwner().isEquivalentTo(
                                            com.example.backend.scim.domain.ScimLoginState.class)))
            .allowEmptyShould(true)
            .because("the login-state module decides the login state a directory write leaves, so"
                    + " a Reactivation through PUT and through PATCH is one rule (issue #43)");

    /**
     * An audit event is constructed inside the audit slice and nowhere else.
     *
     * <p>The boundary rule above is only worth having while
     * {@link com.example.backend.audit.domain.AuditEvent} is unreachable from
     * outside: a caller that could build an event itself could put anything in the
     * textual fields the boundary keeps it away from.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule audit_events_are_built_only_inside_the_audit_slice =
        noClasses()
            .that().resideOutsideOfPackage("com.example.backend.audit..")
            .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.backend.audit.domain.AuditEvent")
            .allowEmptyShould(true)
            .because("The audit slice owns what goes into an event body; a caller says only"
                    + " what happened, through AuditTrail");

    /**
     * A connector token's stored form never reaches a web adapter.
     *
     * <p>{@link com.example.backend.scim.domain.ConnectorTokenDigest} and the token
     * aggregate that holds one are the two types a credential's stored form lives in. A
     * controller that depended on either could render it, and a token hash in a response
     * body is a credential leak even though it is not the credential: it is offline-
     * crackable in a way the 256-bit value is not only because nothing else about the
     * value is known.
     *
     * <p>Held as a rule rather than by review because the safe shape already exists —
     * {@code ConnectorTokenSummary} has no field a digest could occupy — and what a rule
     * adds is that a future adapter cannot reach around it by taking the domain type
     * directly. The exemptions are written as name PATTERNS because ArchUnit treats a
     * nested class as its own class, so an exact name would miss a record nested in a
     * controller.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_connector_token_digest_never_reaches_a_web_adapter =
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.scim\\.domain\\."
                        + "(ConnectorTokenDigest|ScimConnectorToken)(\\$.*)?")
            .allowEmptyShould(true)
            .because("A web adapter returns projections that have no field a token digest"
                    + " could be written into; reaching the domain type directly is how"
                    + " that guarantee would be bypassed");

    /**
     * A SCIM User's credential never reaches a web adapter.
     *
     * <p>{@link com.example.backend.scim.domain.ScimUser} is the one type a User's password
     * hash lives in, and the SCIM adapter renders whatever it is handed. Handing it the
     * projection instead — {@code ScimUserResource}, which has no field a hash could occupy
     * — is what makes "the password never appears in any response" a property of the shape
     * rather than of the renderer's care.
     *
     * <p>Held as a rule rather than by review because the safe shape already exists and what
     * a rule adds is that a future handler cannot reach around it by taking the domain type
     * directly. The name is a PATTERN because ArchUnit treats a nested class as its own
     * class, so an exact name would miss a record nested in a controller.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_scim_user_credential_never_reaches_a_web_adapter =
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.scim\\.domain\\.ScimUser(\\$.*)?")
            .allowEmptyShould(true)
            .because("A web adapter renders the projection, which has no field a password"
                    + " hash could be written into; reaching the aggregate directly is how"
                    + " that guarantee would be bypassed");

    /**
     * Epic's tokens never reach a response.
     *
     * <p>{@link com.example.backend.auth.domain.EpicTokenSet} holds the access token, refresh
     * token and {@code id_token} an Epic Login kept for its session (ADR 0012, D29), and
     * {@link com.example.backend.auth.domain.EpicTokens} hands them out. They are
     * for the backend to act at Epic on the clinician's behalf, never for the browser, so no REST
     * controller may depend on either: a handler that could take them could render them. The one
     * web adapter that writes them, Epic Login's success handler, is a filter-chain handler and
     * answers only with a redirect.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule epic_tokens_never_reach_a_rest_controller =
        noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.auth\\.domain\\."
                        + "(EpicTokenSet|EpicTokens)(\\$.*)?")
            .allowEmptyShould(true)
            .because("ADR 0012, D29: Epic's tokens are held server-side and"
                    + " never returned to the browser, so no handler can be handed them");

    /**
     * The application chain knows Epic Login through one seam.
     *
     * <p>Whether Epic Login is on, and everything it adds to the chain while it is — the release
     * gate while off; the callback filter, {@code oauth2Login}, its routes' rules and its handlers
     * while on — is the Epic module's, behind {@code EpicLogin#applyTo}. The shared chain makes that
     * one call and names no other Epic type, so turning Epic Login on or off, or changing what it
     * adds, never touches the chain every other request goes through.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_application_chain_knows_epic_login_through_one_seam =
        noClasses()
            .that().haveFullyQualifiedName("com.example.backend.auth.config.SecurityConfig")
            .should().dependOnClassesThat(DescribedPredicate.describe(
                    "are Epic types other than EpicLogin",
                    (com.tngtech.archunit.core.domain.JavaClass type) ->
                            type.getPackageName().startsWith("com.example.backend.auth.epic")
                                    && !type.getName().equals(
                                            "com.example.backend.auth.epic.EpicLogin")))
            .because("Epic Login plugs into the application chain at one seam, EpicLogin, which"
                    + " owns its gate, routes and handlers");

    /**
     * A connector token's stored form does not reach the audit slice either.
     *
     * <p>The audit boundary already refuses a {@code String}, which is what a plaintext
     * value is. This closes the other shape: an event body cannot be handed a digest or
     * a token aggregate to render, so "no bearer value in an audit event" holds for the
     * hash as well as for the value.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_audit_slice_never_sees_a_connector_token =
        noClasses()
            .that().resideInAPackage("com.example.backend.audit..")
            .should().dependOnClassesThat()
                .resideInAPackage("com.example.backend.scim..")
            .allowEmptyShould(true)
            .because("The audit slice records a connector by its stable id and has no"
                    + " reason to reach the credential types at all");

    /**
     * The self-read's projection has nowhere to put lockout state or a failure run.
     *
     * <p>{@code SelfRecord} is serialised as-is, so what it cannot hold cannot reach the wire.
     * Two halves, because either alone leaves a way round: a component NAMED for the lock or
     * the run, and a component TYPED as the login state that carries both. The name is a
     * pattern so the nested {@code Group} record is held too.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_self_read_has_no_field_for_lockout_or_failures =
        noFields()
            .that().areDeclaredInClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.auth\\.application\\.SelfRecord(\\$.*)?")
            .should().haveNameMatching("(?i).*(lock|fail|attempt).*")
            .because("Telling a caller how close it is to a lock helps an attacker guessing its"
                    + " password more than the owner; the self-read omits it by shape");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_self_read_cannot_carry_the_login_state =
        noClasses()
            .that().haveNameMatching("com\\.example\\.backend\\.auth\\.application\\.SelfRecord(\\$.*)?")
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.scim\\.domain\\.(ScimLoginState|ScimUser)(\\$.*)?")
            .because("The login state holds the failure run, the lock and the hash; a projection"
                    + " component typed as it would publish all three under an innocent name");

    /**
     * The self-read's handlers take nothing from the request that could name a User.
     *
     * <p>The User is the one the session belongs to. A path variable, query parameter, header or
     * body on this adapter would be the first place a client-supplied identifier could enter, so
     * the rule refuses the parameter rather than trusting a handler to ignore it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_self_read_takes_no_identifier_from_the_request =
        methods()
            .that().areDeclaredIn(com.example.backend.auth.controller.SelfController.class)
            .and(DescribedPredicate.describe("are request handlers",
                    (JavaMethod method) -> method.isMetaAnnotatedWith(
                            org.springframework.web.bind.annotation.RequestMapping.class)))
            .should(new ArchCondition<JavaMethod>("declare no request-bound parameter") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    method.getParameters().stream()
                            .filter(parameter -> parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.PathVariable.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestParam.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestHeader.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestBody.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.CookieValue.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.ModelAttribute.class)
                                    || parameter.getRawType().isEquivalentTo(java.util.UUID.class)
                                    || parameter.getRawType().isEquivalentTo(String.class))
                            .forEach(parameter -> events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " binds " + parameter
                                            + " from the request")));
                }
            })
            .because("The self-read resolves the User from the session alone, so no request value"
                    + " can name somebody else");

    /**
     * A session is signed in only as part of a whole Login.
     *
     * <p>ADR 0001: {@code SessionEstablishment} rotates the session id, saves the security context
     * and sets the principal index, and {@code LoginCompletion} is its one caller, so the decision,
     * the audit record and the refusal handling always surround it. Package-private keeps it out of
     * other packages; this keeps it from the controllers beside it in {@code auth.controller}.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_login_completion_establishes_a_session =
        noClasses()
            .that().haveNameNotMatching("com\\.example\\.backend\\.auth\\.controller\\.LoginCompletion(\\$.*)?")
            .and().haveNameNotMatching("com\\.example\\.backend\\.auth\\.controller\\.SessionEstablishment(\\$.*)?")
            .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.backend.auth.controller.SessionEstablishment")
            .because("ADR 0001: no adapter can sign a session in without the rest of the Login"
                    + " around it");

    /**
     * The login surface ends the caller's session through {@code SessionEnding} alone.
     *
     * <p>Logout, the end of a session after a password change, and a Login that signed nobody in
     * each name a {@code SessionEnding.Reason}; the module runs the record, the invalidation, the
     * context clear, the cookie expiry and {@code Clear-Site-Data} in one order. A handler that
     * invalidated, cleared or expired by hand would be a second copy of the sequence, which is how
     * the copies that preceded the module drifted apart.
     *
     * <p>One named exception: {@code AbsoluteSessionLifetimeFilter}, a chain filter rather than a
     * handler, which ends a session that outlived its policy before the security context is loaded
     * and lets the request go on as a stranger's. It expires no cookie, because the same request
     * may go on to open the next session, and it records its own {@code session-end}. It ends no
     * session at the caller's request, so it is not one of the module's reasons.
     *
     * <p>And a second: {@code EpicLaunchController}'s accepted launch, which replaces the session
     * rather than ending it (ADR 0012, D9). It invalidates the session the browser held and opens
     * the Login's new one in the same response, whose cookie names the new session; expiring the
     * cookie there would contradict it. Its refusals end the session through
     * {@code SessionEnding}, like every other Login that signed nobody in.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_session_ending_ends_the_callers_session =
        noClasses()
            .that().resideInAPackage("com.example.backend.auth..")
            .and().haveNameNotMatching("com\\.example\\.backend\\.auth\\.controller\\.SessionEnding(\\$.*)?")
            .and().doNotHaveFullyQualifiedName(
                    "com.example.backend.auth.controller.AbsoluteSessionLifetimeFilter")
            .and().doNotHaveFullyQualifiedName(
                    "com.example.backend.auth.epic.controller.EpicLaunchController")
            .should().callMethod(jakarta.servlet.http.HttpSession.class, "invalidate")
            .orShould().callMethod(
                    org.springframework.security.core.context.SecurityContextHolder.class,
                    "clearContext")
            .because("every way the login surface ends the caller's session runs SessionEnding's"
                    + " one sequence for a stated reason");

    /**
     * {@code SignedInSession} names one thing: the domain module that owns the signed-in session's
     * attributes. A second type of that name — a nested pairing of an authentication and a
     * session, say — reads at every call site as the module, and is not it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule one_type_is_named_signed_in_session =
        classes()
            .that().haveSimpleName("SignedInSession")
            .should().haveFullyQualifiedName("com.example.backend.auth.domain.SignedInSession")
            .because("the signed-in session module is the one type of that name");

    /**
     * The signed-in session's attributes are adapted twice, once per session API: the servlet
     * container's {@code HttpSession}, for the web handlers and the application chain's session
     * filters alike (the filters are web-layer beans the security configuration places by name),
     * and Spring Session's stored session, read-only. A third would be a copy that can drift.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_signed_in_session_has_one_adapter_per_session_api =
        classes()
            .that().implement("com.example.backend.auth.domain.SignedInSession$Attributes")
            .should().haveFullyQualifiedName("com.example.backend.auth.controller.HttpSessionAttributes")
            .orShould().haveFullyQualifiedName(
                    "com.example.backend.auth.infrastructure.session.SpringSessionAttributes")
            .because("one servlet adapter and one Spring Session adapter serve every reader of the"
                    + " signed-in session");

    /** The session cookie is expired in one place, beside the invalidation it follows. */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_session_ending_expires_the_session_cookie =
        noClasses()
            .that().haveNameNotMatching("com\\.example\\.backend\\.auth\\.controller\\.SessionEnding(\\$.*)?")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "CookieSerializer.writeCookieValue",
                    (JavaMethodCall call) -> call.getTargetOwner().isAssignableTo(
                                    org.springframework.session.web.http.CookieSerializer.class)
                            && call.getName().equals("writeCookieValue")))
            .because("the session cookie is expired by SessionEnding, as one step of ending the"
                    + " caller's session, never on its own");

    // ADR 0001: count login attempts on the login path
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule web_adapters_never_call_authentication_manager =
        noClasses()
            .that().resideInAnyPackage("..controller..", "..application..")
            .and().haveNameNotMatching("com\\.example\\.backend\\.auth\\.application\\.LoginService(\\$.*)?")
            .should().dependOnClassesThat().areAssignableTo(
                    org.springframework.security.authentication.AuthenticationManager.class)
            .allowEmptyShould(true)
            .because("ADR 0001: submitted credentials are authenticated only through LoginService,"
                    + " which is where every attempt is counted");

    // backend/AGENTS.md: sessions end through the AccountSessions port
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule application_never_reaches_spring_session =
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.session..")
            .allowEmptyShould(true)
            .because("backend/AGENTS.md: application services end sessions through the"
                    + " AccountSessions port, never a session repository");

    // ADR 0002: revoke sessions after commit
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule after_commit_only_through_the_port =
        noClasses()
            .that().resideOutsideOfPackage("..infrastructure.transaction..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.transaction.support.TransactionSynchronization")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.transaction.support.TransactionSynchronizationManager")
            .allowEmptyShould(true)
            .because("ADR 0002: after-commit work is registered through the AfterCommit port, so"
                    + " there is one place that decides what runs once a transaction commits");

    // ADR 0002: revoke sessions after commit
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_transactional_event_listeners =
        noMethods()
            .should().beAnnotatedWith("org.springframework.transaction.event.TransactionalEventListener")
            .allowEmptyShould(true)
            .because("ADR 0002: after-commit work goes through the AfterCommit port, not an event"
                    + " listener the publisher cannot see");

    // ADR 0001: count login attempts on the login path
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_authentication_event_listeners =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(
                    "org.springframework.security.authentication.event..")
            .allowEmptyShould(true)
            .because("ADR 0001: login attempts are recorded on the login path itself, never by"
                    + " an authentication event listener");
}
